package ai.nanosearch.launcher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.BatteryManager
import android.provider.MediaStore
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Background job that reads the text in photos so it can be searched. Every photo first gets the cheap text detector; only pictures
 * that actually contain text go on to the (slower) recogniser, so landscapes and portraits cost little. Screenshots are done first.
 * While charging it works through the whole library; on battery it does a small batch per launch and nothing below 30%.
 */
class PhotoTextReader(private val context: Context, private val index: SearchIndex) {
    fun run(shouldStop: () -> Boolean) {
        val ocr = Services.ocr
        if (!ocr.available) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val budget = when {
            charging -> Int.MAX_VALUE
            level >= 30 -> 25
            else -> 0
        }
        if (budget == 0) return

        // The image model has already fingerprinted each photo, so it can say cheaply whether a photo looks like it holds text.
        // Screenshots are always read; everything else only if it looks texty (this is what keeps a big library affordable).
        val gate: List<FloatArray>? = if (Services.clip.available) GATE_PROMPTS.mapNotNull { Services.clip.encodeText(it) }.takeIf { it.size == GATE_PROMPTS.size } else null
        val vectors = if (gate != null) index.photoVectors() else emptyMap()
        val t0 = System.nanoTime()
        var done = 0
        var withText = 0
        var skipped = 0
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        while (done < budget && !shouldStop()) {
            val pending = index.pendingOcr(100)
            if (pending.isEmpty()) break
            var progressed = false
            for (task in pending) {
                if (done >= budget || shouldStop()) break
                if (!task.screenshot && gate != null) {
                    val v = vectors[task.id] ?: continue // not fingerprinted yet: leave it for a later run
                    if (gate.maxOf { g -> dot(g, v) } < TEXT_GATE) {
                        index.putOcr(task, "")
                        skipped++; done++; progressed = true
                        continue
                    }
                }
                val bitmap = decode(ContentUris.withAppendedId(base, task.id))
                var text = ""
                if (bitmap != null) {
                    val boxes = ocr.detect(bitmap)
                    if (boxes.isNotEmpty()) text = ocr.read(bitmap, boxes).joinToString("\n") { it.text }
                    bitmap.recycle()
                }
                // Recorded even when empty, so a photo without text is not looked at again.
                index.putOcr(task, text)
                if (text.isNotBlank()) withText++
                done++; progressed = true
                Services.clipUsed()
                if (!charging) Thread.sleep(150)
            }
            if (!progressed) break // everything left is waiting for a fingerprint
        }
        if (done > 0) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i("nanosearch", "read $done photos for text in ${ms}ms (${ms / done} ms each): $withText had text, $skipped skipped as not texty; ${index.ocrDone()} done, ${index.ocrWithText()} with text in total")
        }
    }

    /** Full decode (so small print is legible), scaled down if huge, with the photo's rotation applied. */
    private fun decode(uri: android.net.Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
    }.onFailure { Log.w("nanosearch", "ocr decode failed: $it") }.getOrNull()

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private companion object {
        const val MAX_SIDE = 2000
        // Measured: images with text scored 0.13-0.26 on these prompts, natural photos 0.06-0.11. The cutoff leans towards reading.
        const val TEXT_GATE = 0.11f
        val GATE_PROMPTS = listOf("a screenshot with text", "a photo of a page of text, a sign or a receipt")
    }
}
