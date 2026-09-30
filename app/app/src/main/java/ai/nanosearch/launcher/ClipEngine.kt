package ai.nanosearch.launcher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.sqrt

/**
 * The OpenAI CLIP byte-pair tokenizer (also used by MobileCLIP), built from the model's own tokenizer.json.
 * Text becomes 77 token ids: start, the words, end, then zero padding.
 */
class ClipTokenizer(json: String) {
    private val vocab = HashMap<String, Int>(70_000)
    private val ranks = HashMap<String, Int>(70_000)
    private val byteToChar: Array<String> = bytesToUnicode()
    private val words = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")
    private val cache = HashMap<String, IntArray>()

    init {
        val model = JSONObject(json).getJSONObject("model")
        val v = model.getJSONObject("vocab")
        for (k in v.keys()) vocab[k] = v.getInt(k)
        val merges: JSONArray = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val m = merges.get(i)
            ranks[if (m is JSONArray) "${m.getString(0)} ${m.getString(1)}" else m as String] = i
        }
    }

    fun encode(text: String): LongArray {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
        val ids = ArrayList<Int>()
        ids.add(START)
        val m = words.matcher(clean)
        while (m.find()) for (id in bpe(m.group())) ids.add(id)
        if (ids.size > LENGTH - 1) { while (ids.size > LENGTH - 1) ids.removeAt(ids.size - 1) }
        ids.add(END)
        return LongArray(LENGTH) { if (it < ids.size) ids[it].toLong() else 0L }
    }

    private fun bpe(word: String): IntArray = cache.getOrPut(word) {
        val symbols = ArrayList<String>()
        for (b in word.toByteArray(Charsets.UTF_8)) symbols.add(byteToChar[b.toInt() and 0xFF])
        symbols[symbols.size - 1] = symbols.last() + "</w>"
        while (symbols.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until symbols.size - 1) {
                val r = ranks["${symbols[i]} ${symbols[i + 1]}"] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            symbols[best] = symbols[best] + symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        IntArray(symbols.size) { vocab[symbols[it]] ?: END }
    }

    private fun bytesToUnicode(): Array<String> {
        val keep = ArrayList<Int>()
        for (c in '!'.code..'~'.code) keep.add(c)
        for (c in '¡'.code..'¬'.code) keep.add(c)
        for (c in '®'.code..'ÿ'.code) keep.add(c)
        val out = Array(256) { "" }
        var extra = 0
        for (b in 0 until 256) out[b] = String(Character.toChars(if (b in keep) b else 256 + extra++))
        return out
    }

    companion object {
        const val START = 49406
        const val END = 49407
        const val LENGTH = 77
    }
}

/**
 * Image-and-text matching on the phone: MobileCLIP-S0 through ONNX Runtime. Both a photo and a phrase become 512 numbers with unit
 * length, and the dot product of the two says how well they match, so "a photo of a dog" finds dog photos without any labels.
 * Models are full precision on purpose: the 8-bit versions were tested and matched no better than chance.
 * Files: files/models/clip-image.onnx and clip-text.onnx; the tokenizer ships in the app.
 */
class ClipEngine(private val context: Context) {
    private val dir = File(context.filesDir, "models")
    private val imageFile get() = File(dir, "clip-image.onnx")
    private val textFile get() = File(dir, "clip-text.onnx")
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val tokenizer by lazy { ClipTokenizer(context.assets.open("clip_tokenizer.json").bufferedReader().use { it.readText() }) }
    private val textLock = Any()
    private val imageLock = Any()
    private var text: OrtSession? = null
    private var image: OrtSession? = null
    private val textCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 64
    }

    val available get() = imageFile.exists() && textFile.exists()

    fun encodeText(query: String): FloatArray? = synchronized(textLock) {
        textCache[query]?.let { return it }
        runCatching {
            val session = text ?: env.createSession(textFile.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).also { text = it }
            val ids = tokenizer.encode(query)
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                session.run(mapOf("input_ids" to input)).use { out -> unit((out[0].value as Array<FloatArray>)[0]) }
            }.also { textCache[query] = it }
        }.onFailure { Log.w(TAG, "clip text failed: $it") }.getOrNull()
    }

    /** [bitmap] is any size; it is scaled so its short side is 256, centre-cropped to 256x256, and fed in as 0..1 RGB. */
    fun encodeImage(bitmap: Bitmap): FloatArray? = synchronized(imageLock) {
        runCatching {
            val session = image ?: env.createSession(imageFile.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }).also { image = it }
            val scale = SIZE / minOf(bitmap.width, bitmap.height).toFloat()
            val w = maxOf(SIZE, Math.round(bitmap.width * scale))
            val h = maxOf(SIZE, Math.round(bitmap.height * scale))
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, (w - SIZE) / 2, (h - SIZE) / 2, SIZE, SIZE)
            if (scaled !== bitmap) scaled.recycle()
            val plane = SIZE * SIZE
            val data = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                data[i] = ((p shr 16) and 0xFF) / 255f
                data[plane + i] = ((p shr 8) and 0xFF) / 255f
                data[2 * plane + i] = (p and 0xFF) / 255f
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
                session.run(mapOf("pixel_values" to input)).use { out -> unit((out[0].value as Array<FloatArray>)[0]) }
            }
        }.onFailure { Log.w(TAG, "clip image failed: $it") }.getOrNull()
    }

    fun unloadText() = synchronized(textLock) { text?.close(); text = null }
    fun unloadImage() = synchronized(imageLock) { image?.close(); image = null }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val inv = (1.0 / sqrt(n)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    /** Debug: what the tokenizer produces, to compare against the reference implementation. */
    fun debugTokens(query: String) = tokenizer.encode(query).takeWhile { it != 0L || false }.joinToString(",")

    private companion object {
        const val TAG = "nanosearch"
        const val SIZE = 256
    }
}
