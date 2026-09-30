package ai.nanosearch.launcher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the text in a picture, on the phone: PaddleOCR (PP-OCRv4, Apache-2.0) through ONNX Runtime. Two small models: a detector that
 * paints a "there is text here" map (4.7 MB) and a recogniser that reads one cropped line (10.9 MB, with its own character list).
 * The detector's map is turned into line boxes here without any image library: threshold, group touching pixels, grow each group a
 * little. That is slightly cruder than the reference for tilted text and plenty for screenshots, receipts and documents.
 * Files: files/models/ocr-det.onnx and ocr-rec.onnx.
 */
class OcrEngine(context: Context) {
    data class Line(val text: String, val confidence: Float, val top: Float, val left: Float)

    private val dir = File(context.filesDir, "models")
    private val detFile get() = File(dir, "ocr-det.onnx")
    private val recFile get() = File(dir, "ocr-rec.onnx")
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dictionary: Array<String> = emptyArray()

    val available get() = detFile.exists() && recFile.exists()

    /** Step one on its own: does this picture have text worth reading? Cheap (the detector only), so every photo gets it. */
    fun detect(bitmap: Bitmap): List<Box> = synchronized(lock) { runCatching { findBoxes(bitmap) }.onFailure { Log.w(TAG, "ocr detect failed: $it") }.getOrDefault(emptyList()) }

    /** Reads every line of text, in reading order. Lines the recogniser is unsure of are dropped. */
    fun read(bitmap: Bitmap, boxes: List<Box> = detect(bitmap), deadlineMs: Long = 15_000): List<Line> = synchronized(lock) {
        runCatching {
            val started = System.nanoTime()
            // Dense pages (a newspaper) are capped: the biggest lines first, then put back in reading order.
            val chosen = boxes.sortedByDescending { (it.x1 - it.x0) * (it.y1 - it.y0) }.take(MAX_LINES).sortedWith(compareBy({ (it.y0 / 10).roundToInt() }, { it.x0 }))
            val lines = ArrayList<Line>()
            for (b in chosen) {
                if ((System.nanoTime() - started) / 1_000_000 > deadlineMs) break
                val (text, conf) = recognise(bitmap, b)
                if (conf >= MIN_CONFIDENCE && text.trim().length >= 2) lines.add(Line(text.trim(), conf, b.y0, b.x0))
            }
            lines
        }.onFailure { Log.w(TAG, "ocr read failed: $it") }.getOrDefault(emptyList())
    }

    fun unload() = synchronized(lock) { det?.close(); rec?.close(); det = null; rec = null }

    class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float)

    // ---- detection

    private fun findBoxes(src: Bitmap): List<Box> {
        val session = det ?: env.createSession(detFile.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).also { det = it }
        val w = src.width
        val h = src.height
        val s = min(1f, DET_LIMIT / max(h, w).toFloat())
        val nh = max(32, (h * s / 32).roundToInt() * 32)
        val nw = max(32, (w * s / 32).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val px = IntArray(nw * nh)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== src) scaled.recycle()
        // The model was trained on BGR images normalised with these per-channel values.
        val plane = nw * nh
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = (((p and 0xFF) / 255f) - 0.485f) / 0.229f            // B
            data[plane + i] = ((((p shr 8) and 0xFF) / 255f) - 0.456f) / 0.224f // G
            data[2 * plane + i] = ((((p shr 16) and 0xFF) / 255f) - 0.406f) / 0.225f // R
        }
        val prob: FloatArray = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { out ->
                val v = out[0].value as Array<Array<Array<FloatArray>>>
                FloatArray(plane).also { flat -> for (y in 0 until nh) System.arraycopy(v[0][0][y], 0, flat, y * nw, nw) }
            }
        }

        // Group touching "text" pixels (8-neighbourhood) by flood fill; each group becomes one line box.
        val visited = BooleanArray(plane)
        val stack = IntArray(plane)
        val boxes = ArrayList<Box>()
        for (start in 0 until plane) {
            if (visited[start] || prob[start] <= DET_THRESHOLD) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var x0 = nw; var y0 = nh; var x1 = 0; var y1 = 0
            var sum = 0.0
            var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cy = cur / nw
                val cx = cur - cy * nw
                if (cx < x0) x0 = cx; if (cx > x1) x1 = cx; if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
                sum += prob[cur]; count++
                for (dy in -1..1) for (dx in -1..1) {
                    val ny = cy + dy; val nx = cx + dx
                    if (ny < 0 || ny >= nh || nx < 0 || nx >= nw) continue
                    val ni = ny * nw + nx
                    if (!visited[ni] && prob[ni] > DET_THRESHOLD) { visited[ni] = true; stack[sp++] = ni }
                }
            }
            val bw = x1 + 1 - x0
            val bh = y1 + 1 - y0
            if (min(bw, bh) < 3) continue
            // Keep only groups the detector is confident about (mean probability over the group's own pixels).
            val score = (sum / count).toFloat()
            if (score < BOX_THRESHOLD) continue
            // Unclip: the detector paints a shrunken core of each line, so grow it back by a distance based on its shape.
            val d = (bw * bh * UNCLIP) / (2f * (bw + bh))
            val bx0 = max(0f, (x0 - d) / nw * w)
            val by0 = max(0f, (y0 - d) / nh * h)
            val bx1 = min(w.toFloat(), (x1 + 1 + d) / nw * w)
            val by1 = min(h.toFloat(), (y1 + 1 + d) / nh * h)
            if (min(bx1 - bx0, by1 - by0) > 5) boxes.add(Box(bx0, by0, bx1, by1, score))
        }
        return boxes.sortedWith(compareBy({ (it.y0 / 10).roundToInt() }, { it.x0 }))
    }

    // ---- recognition

    private fun recognise(src: Bitmap, b: Box): Pair<String, Float> {
        val session = rec ?: env.createSession(recFile.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).also {
            rec = it
            val chars = it.metadata.customMetadata["character"]?.split("\n") ?: emptyList()
            dictionary = (listOf("") + chars + listOf(" ")).toTypedArray() // index 0 is the blank; the space is appended
        }
        val x0 = b.x0.roundToInt().coerceIn(0, src.width - 1)
        val y0 = b.y0.roundToInt().coerceIn(0, src.height - 1)
        val cw = (b.x1.roundToInt() - x0).coerceIn(1, src.width - x0)
        val ch = (b.y1.roundToInt() - y0).coerceIn(1, src.height - y0)
        var crop = Bitmap.createBitmap(src, x0, y0, cw, ch)
        if (ch >= 1.5f * cw) { // a tall box holds sideways text
            val r = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true)
            crop.recycle(); crop = r
        }
        val tw = min(2048, max(16, ceil(48f * crop.width / crop.height).toInt()))
        val fitted = Bitmap.createScaledBitmap(crop, tw, 48, true)
        if (crop !== fitted) crop.recycle()
        val px = IntArray(tw * 48)
        fitted.getPixels(px, 0, tw, 0, 0, tw, 48)
        fitted.recycle()
        val plane = tw * 48
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
            data[plane + i] = ((((p shr 8) and 0xFF) / 255f) - 0.5f) / 0.5f
            data[2 * plane + i] = ((((p shr 16) and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        val out: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 48, tw.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { (it[0].value as Array<Array<FloatArray>>)[0] }
        }
        // CTC decoding: best class per step, drop repeats and blanks.
        val sb = StringBuilder()
        var confSum = 0f
        var kept = 0
        var prev = 0
        for (step in out) {
            var best = 0
            for (c in 1 until step.size) if (step[c] > step[best]) best = c
            if (best != 0 && best != prev && best < dictionary.size) { sb.append(dictionary[best]); confSum += step[best]; kept++ }
            prev = best
        }
        return sb.toString() to (if (kept > 0) confSum / kept else 0f)
    }

    private companion object {
        const val TAG = "nanosearch"
        const val DET_LIMIT = 960f
        const val DET_THRESHOLD = 0.3f
        const val BOX_THRESHOLD = 0.6f
        const val UNCLIP = 1.6f
        const val MIN_CONFIDENCE = 0.7f
        const val MAX_LINES = 80
    }
}
