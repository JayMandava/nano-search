package ai.nanosearch.launcher.clocks.packs

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object FacePackLimits {
    const val MAX_ZIP_BYTES = 4L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 12L * 1024 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_PNG_BYTES = 2 * 1024 * 1024
    const val MAX_PNGS = 4
    const val MAX_IMAGE_SIDE = 1024
    const val MAX_IMAGE_PIXELS = 2L * 1024 * 1024
    const val MAX_PACKS = 20
    const val MAX_STORAGE_BYTES = 100L * 1024 * 1024
}

class FacePackException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Data only. All drawing and time interpretation are implemented by the native analog-v1 renderer. */
data class FacePackSpec(
    val schemaVersion: Int,
    val template: String,
    val id: String,
    val version: Int,
    val name: String,
    val credit: String?,
    val palette: FacePackPalette,
    val hour: FacePackHand,
    val minute: FacePackHand,
    val second: FacePackHand,
    val pivot: FacePackPivot,
    val date: FacePackDate?,
    val assets: FacePackAssets,
    val gears: List<FacePackGear>,
) {
    fun assetNames(): Set<String> = listOfNotNull(assets.base, assets.overlay, assets.preview)
        .plus(gears.mapNotNull { it.image }).toSet()

    companion object {
        fun parse(bytes: ByteArray): FacePackSpec {
            checkPack(bytes.size <= FacePackLimits.MAX_MANIFEST_BYTES, "Manifest exceeds 64 KiB")
            val source = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (e: Exception) { throw FacePackException("Manifest must be UTF-8", e) }
            val root = try {
                JsonReader(StringReader(source)).use { reader ->
                    reader.isLenient = false
                    val result = StrictJson.read(reader)
                    checkPack(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON")
                    Obj(result)
                }
            } catch (e: FacePackException) { throw e }
            catch (e: Exception) { throw FacePackException("Invalid manifest JSON", e) }
            root.keys("schemaVersion", "template", "id", "version", "name", "credit", "palette", "hour", "minute", "second", "pivot", "date", "assets", "gears")
            val schema = root.integer("schemaVersion", 1, 1)
            val template = root.text("template", 16)
            checkPack(template == "analog-v1", "Unsupported face template")
            val id = root.text("id", 48)
            checkPack(ID.matches(id), "Invalid manifest id")
            val palette = root.obj("palette").let { p ->
                p.keys("background", "rim", "ticks")
                FacePackPalette(p.color("background"), p.color("rim"), p.color("ticks"))
            }
            val pivot = root.obj("pivot").let { p -> p.keys("radius", "color"); FacePackPivot(p.number("radius", 0f, 100f), p.color("color")) }
            val assets = root.optionalObj("assets")?.let { a ->
                a.keys("base", "overlay", "preview")
                FacePackAssets(a.asset("base"), a.asset("overlay"), a.asset("preview"))
            } ?: FacePackAssets()
            val date = root.optionalObj("date")?.let { d ->
                d.keys("x", "y", "width", "height", "textSize", "color", "background")
                val x = d.number("x", 0f, 1000f); val y = d.number("y", 0f, 1000f)
                val width = d.number("width", 1f, 500f); val height = d.number("height", 1f, 200f)
                checkPack(x + width <= 1000f && y + height <= 1000f, "Date aperture exceeds face")
                FacePackDate(x, y, width, height, d.number("textSize", 1f, 160f), d.color("color"), d.color("background"))
            }
            val gears = root.optionalList("gears").map { raw ->
                Obj(raw).let { g ->
                    g.keys("x", "y", "radius", "degreesPerSecond", "color", "teeth", "image")
                    val x = g.number("x", 0f, 1000f); val y = g.number("y", 0f, 1000f)
                    val radius = g.number("radius", 1f, 500f)
                    checkPack(x - radius >= 0 && y - radius >= 0 && x + radius <= 1000 && y + radius <= 1000, "Gear exceeds face")
                    FacePackGear(x, y, radius, g.number("degreesPerSecond", -60f, 60f), g.color("color"), g.integer("teeth", 8, 48), g.asset("image"))
                }
            }
            checkPack(gears.size <= 8, "At most eight decorative gears")
            val result = FacePackSpec(schema, template, id, root.integer("version", 1, Int.MAX_VALUE), root.text("name", 64), root.optionalText("credit", 160), palette,
                hand(root.obj("hour")), hand(root.obj("minute")), hand(root.obj("second")), pivot, date, assets, java.util.Collections.unmodifiableList(gears))
            checkPack(result.assetNames().size <= FacePackLimits.MAX_PNGS, "At most four PNG assets")
            return result
        }
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,47}")
        private fun hand(h: Obj): FacePackHand {
            h.keys("shape", "length", "width", "color")
            val shape = when (h.text("shape", 8)) { "baton" -> FacePackHandShape.BATON; "sword" -> FacePackHandShape.SWORD; else -> throw FacePackException("Unknown hand shape") }
            return FacePackHand(shape, h.number("length", 1f, 490f), h.number("width", 1f, 100f), h.color("color"))
        }
    }
}

data class FacePackPalette(val background: Int, val rim: Int, val ticks: Int)
enum class FacePackHandShape { BATON, SWORD }
data class FacePackHand(val shape: FacePackHandShape, val length: Float, val width: Float, val color: Int)
data class FacePackPivot(val radius: Float, val color: Int)
/** x/y are the aperture's top-left; date always reflects the host's actual local day. */
data class FacePackDate(val x: Float, val y: Float, val width: Float, val height: Float, val textSize: Float, val color: Int, val background: Int)
data class FacePackAssets(val base: String? = null, val overlay: String? = null, val preview: String? = null)
/** Decorative mechanics, independent of real watch engineering or chronograph state. */
data class FacePackGear(val x: Float, val y: Float, val radius: Float, val degreesPerSecond: Float, val color: Int, val teeth: Int, val image: String? = null)

internal fun checkPack(condition: Boolean, message: String) { if (!condition) throw FacePackException(message) }
internal fun checkCancelled(cancel: () -> Boolean) { if (cancel() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Face pack operation cancelled") }
internal val PACK_ASSET_NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,59}\\.png")

private class Obj(raw: Any?) {
    @Suppress("UNCHECKED_CAST") private val values = raw as? Map<String, Any?> ?: throw FacePackException("Expected JSON object")
    fun keys(vararg allowed: String) { checkPack(values.keys.all { it in allowed }, "Unknown manifest field") }
    fun text(key: String, max: Int): String {
        val value = values[key] as? String ?: throw FacePackException("Expected string: $key")
        checkPack(value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }, "Invalid text: $key")
        return value
    }
    fun optionalText(key: String, max: Int): String? = if (!values.containsKey(key)) null else text(key, max)
    fun obj(key: String) = Obj(values[key])
    fun optionalObj(key: String): Obj? = if (!values.containsKey(key)) null else obj(key)
    fun optionalList(key: String): List<*> = if (!values.containsKey(key)) emptyList<Any>() else values[key] as? List<*> ?: throw FacePackException("Expected array: $key")
    fun number(key: String, min: Float, max: Float): Float {
        val d = values[key] as? Double ?: throw FacePackException("Expected number: $key")
        checkPack(d.isFinite() && d >= min && d <= max, "Number out of range: $key")
        return d.toFloat()
    }
    fun integer(key: String, min: Int, max: Int): Int {
        val d = values[key] as? Double ?: throw FacePackException("Expected integer: $key")
        checkPack(d.isFinite() && d >= min && d <= max && d == kotlin.math.floor(d), "Integer out of range: $key")
        return d.toInt()
    }
    fun color(key: String): Int {
        val s = text(key, 9)
        checkPack(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(s), "Invalid color: $key")
        return if (s.length == 7) (0xff000000L or s.substring(1).toLong(16)).toInt() else s.substring(1).toLong(16).toInt()
    }
    fun asset(key: String): String? = optionalText(key, 64)?.also { checkPack(PACK_ASSET_NAME.matches(it), "Invalid PNG name") }
}

/** Duplicate fields, unbounded nesting, implicit coercions, and non-JSON numeric literals are rejected. */
private object StrictJson {
    fun read(reader: JsonReader): Any? {
        var nodes = 0
        fun value(depth: Int): Any? {
            checkPack(depth <= 8 && ++nodes <= 2048, "Manifest complexity limit")
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val map = linkedMapOf<String, Any?>(); reader.beginObject()
                    while (reader.hasNext()) { val k = reader.nextName(); checkPack(!map.containsKey(k), "Duplicate JSON field"); map[k] = value(depth + 1) }
                    reader.endObject(); map
                }
                JsonToken.BEGIN_ARRAY -> { val list = arrayListOf<Any?>(); reader.beginArray(); while (reader.hasNext()) list.add(value(depth + 1)); reader.endArray(); list }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString().let { n -> checkPack(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(n), "Invalid JSON number"); n.toDouble().also { checkPack(it.isFinite(), "Nonfinite number") } }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw FacePackException("Invalid JSON value")
            }
        }
        return value(0)
    }
}
