package ai.nanosearch.launcher.clocks.packs

import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

object FacePackLimits {
    const val MAX_ZIP_BYTES = 4L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 12L * 1024 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_LICENSE_BYTES = 32 * 1024
    const val MAX_PNG_BYTES = 2 * 1024 * 1024
    const val MAX_PNGS = 4
    const val MAX_IMAGE_SIDE = 1024
    const val MAX_IMAGE_PIXELS = 1_048_576L
    const val MAX_DECODED_BYTES = 8L * 1024 * 1024
    const val MAX_PACKS = 20
    const val MAX_STORAGE_BYTES = 100L * 1024 * 1024
    const val MAX_RATIO = 100
    const val MAX_JSON_DEPTH = 8
    const val MAX_JSON_VALUES = 1024
}

/** Proven format, integrity, or compatibility failure; safe to quarantine a retained revision. */
class FacePackException(message: String, cause: Throwable? = null, val incompatible: Boolean = false) : IOException(message, cause)

/** Transient device resource failure, not evidence that a pack is corrupt. */
class FacePackResourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Validated styling data, never executable behavior. No compatibility with the discarded WIP schema. */
data class FacePackSpec(
    val format: String,
    val schemaVersion: Int,
    val packId: String,
    val packVersion: Int,
    val name: String,
    val author: String,
    val description: String?,
    val license: String,
    val minAppVersionCode: Int,
    val requires: List<String>,
    val template: String,
    val dial: FacePackDial,
    val hands: FacePackHands,
    val dateWindow: FacePackDateWindow?,
    val assets: List<FacePackAsset>,
) {
    fun assetNames(): Set<String> = assets.mapTo(linkedSetOf()) { it.path }

    companion object {
        const val FORMAT = "nano.clock-face-pack"
        private val PACK_ID = Regex("[a-z][a-z0-9-]{0,63}")
        private val ASSET_KEY = Regex("[a-z][a-z0-9-]{0,31}")
        private val DIGEST = Regex("[0-9a-f]{64}")

        fun parse(bytes: ByteArray): FacePackSpec {
            checkPack(bytes.size <= FacePackLimits.MAX_MANIFEST_BYTES, "Manifest exceeds 64 KiB")
            val root = PackObject(BoundedPackJson(decodePackUtf8(bytes, "Manifest")).read())
            root.keys("format", "schemaVersion", "packId", "packVersion", "name", "author", "description", "license", "minAppVersionCode", "requires", "template", "dial", "hands", "dateWindow", "assets")
            val format = root.string("format")
            checkPack(format == FORMAT, "Unsupported clock face pack format")
            val schema = root.positiveInt("schemaVersion")
            checkPack(schema == 1, "Requires a newer Nano version: unsupported pack schema", incompatible = true)
            val template = root.string("template")
            checkPack(template == "analog-v1", "Requires a newer Nano version: unsupported face template", incompatible = true)
            val packId = root.string("packId")
            checkPack(PACK_ID.matches(packId) && packId !in setOf("steeldial", "carbon01"), "Invalid or reserved pack ID")
            val requires = root.array("requires").map { it as? String ?: throw FacePackException("Capabilities must be strings") }
            checkPack(requires.isNotEmpty() && requires.size <= 4 && requires.toSet().size == requires.size, "Invalid capability list")
            checkPack("analog-v1" in requires, "Missing analog-v1 capability")
            checkPack(requires.all { it == "analog-v1" || it == "png-v1" }, "Requires a newer Nano version: unsupported pack capability", incompatible = true)
            val dial = root.obj("dial").let { d ->
                d.keys("backgroundColor", "textureAsset", "ticks", "tickColor", "numerals", "numeralColor", "font")
                FacePackDial(d.color("backgroundColor"), d.optionalString("textureAsset"),
                    when (d.string("ticks")) {
                        "none" -> FacePackTicks.NONE; "hour" -> FacePackTicks.HOUR; "minute" -> FacePackTicks.MINUTE
                        else -> throw FacePackException("Unknown tick style")
                    }, d.color("tickColor"),
                    when (d.string("numerals")) {
                        "none" -> FacePackNumerals.NONE; "quarters" -> FacePackNumerals.QUARTERS; "all" -> FacePackNumerals.ALL
                        else -> throw FacePackException("Unknown numeral style")
                    }, d.color("numeralColor"),
                    when (d.string("font")) {
                        "system-sans" -> FacePackFont.SYSTEM_SANS
                        "system-condensed" -> FacePackFont.SYSTEM_CONDENSED
                        "system-mono" -> FacePackFont.SYSTEM_MONO
                        else -> throw FacePackException("Unknown font choice")
                    })
            }
            val hands = root.obj("hands").let { h ->
                h.keys("hour", "minute", "second")
                FacePackHands(hand(h.obj("hour")), hand(h.obj("minute")), h.optionalObj("second")?.let(::hand))
            }
            val date = root.optionalObj("dateWindow")?.let { d ->
                d.keys("position", "textColor", "backgroundColor")
                FacePackDateWindow(when (d.string("position")) {
                    "right" -> FacePackDatePosition.RIGHT; "bottom" -> FacePackDatePosition.BOTTOM
                    else -> throw FacePackException("Unknown date window position")
                }, d.color("textColor"), d.color("backgroundColor"))
            }
            val rawAssets = root.array("assets")
            checkPack(rawAssets.size <= FacePackLimits.MAX_PNGS, "Too many PNG assets")
            val assets = rawAssets.map { raw ->
                val a = PackObject(raw)
                a.keys("key", "path", "sha256")
                val key = a.string("key"); val path = a.string("path"); val hash = a.string("sha256")
                checkPack(ASSET_KEY.matches(key), "Invalid asset key")
                checkPack(path == "assets/$key.png", "Invalid PNG asset path")
                checkPack(DIGEST.matches(hash), "Invalid asset SHA-256")
                FacePackAsset(key, path, hash)
            }
            checkPack(assets.map { it.key }.toSet().size == assets.size, "Duplicate asset key")
            val references = listOfNotNull(dial.textureAsset)
            checkPack(references.all { reference -> assets.count { it.key == reference } == 1 }, "Unresolved asset reference")
            checkPack(assets.all { it.key in references }, "Unreferenced asset")
            checkPack(assets.isEmpty() || "png-v1" in requires, "Missing png-v1 capability")
            return FacePackSpec(format, schema, packId, root.positiveInt("packVersion"),
                root.displayText("name", 60), root.displayText("author", 80), root.optionalDisplayText("description", 240),
                root.displayText("license", 80), root.positiveInt("minAppVersionCode"), immutable(requires), template,
                dial, hands, date, immutable(assets))
        }

        private fun hand(h: PackObject): FacePackHand {
            h.keys("shape", "color", "length", "width", "tail")
            val shape = when (h.string("shape")) {
                "bar" -> FacePackHandShape.BAR; "needle" -> FacePackHandShape.NEEDLE
                else -> throw FacePackException("Unknown hand shape")
            }
            return FacePackHand(shape, h.color("color"), h.fraction("length", "0.05", "0.48"),
                h.fraction("width", "0.001", "0.08"), h.fraction("tail", "0", "0.15"))
        }
        private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    }
}

data class FacePackDial(val backgroundColor: Int, val textureAsset: String?, val ticks: FacePackTicks, val tickColor: Int,
    val numerals: FacePackNumerals, val numeralColor: Int, val font: FacePackFont)
enum class FacePackTicks { NONE, HOUR, MINUTE }
enum class FacePackNumerals { NONE, QUARTERS, ALL }
enum class FacePackFont { SYSTEM_SANS, SYSTEM_CONDENSED, SYSTEM_MONO }
enum class FacePackHandShape { BAR, NEEDLE }
data class FacePackHand(val shape: FacePackHandShape, val color: Int, val length: Float, val width: Float, val tail: Float)
data class FacePackHands(val hour: FacePackHand, val minute: FacePackHand, val second: FacePackHand?)
enum class FacePackDatePosition { RIGHT, BOTTOM }
data class FacePackDateWindow(val position: FacePackDatePosition, val textColor: Int, val backgroundColor: Int)
data class FacePackAsset(val key: String, val path: String, val sha256: String)

internal fun checkPack(condition: Boolean, message: String, incompatible: Boolean = false) {
    if (!condition) throw FacePackException(message, incompatible = incompatible)
}
internal fun checkCancelled(cancel: () -> Boolean) {
    if (cancel() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Face pack operation cancelled")
}
internal fun decodePackUtf8(bytes: ByteArray, label: String): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (error: java.nio.charset.CharacterCodingException) {
    throw FacePackException("$label must be valid UTF-8", error)
}

private data class PackNumber(val text: String, val decimal: BigDecimal)

private class PackObject(raw: Any?) {
    @Suppress("UNCHECKED_CAST")
    private val values = raw as? Map<String, Any?> ?: throw FacePackException("Expected JSON object")
    fun keys(vararg allowed: String) { checkPack(values.keys.all { it in allowed }, "Unknown manifest field") }
    fun string(key: String): String = values[key] as? String ?: throw FacePackException("Expected string: $key")
    fun optionalString(key: String): String? = if (values.containsKey(key)) string(key) else null
    fun obj(key: String) = PackObject(values[key])
    fun optionalObj(key: String): PackObject? = if (values.containsKey(key)) obj(key) else null
    fun array(key: String): List<*> = values[key] as? List<*> ?: throw FacePackException("Expected array: $key")
    fun positiveInt(key: String): Int {
        val n = values[key] as? PackNumber ?: throw FacePackException("Expected integer: $key")
        checkPack(POSITIVE_INTEGER.matches(n.text), "Expected positive integer: $key")
        return n.text.toIntOrNull()?.takeIf { it > 0 } ?: throw FacePackException("Integer out of range: $key")
    }
    fun fraction(key: String, min: String, max: String): Float {
        val n = values[key] as? PackNumber ?: throw FacePackException("Expected number: $key")
        checkPack(n.decimal >= BigDecimal(min) && n.decimal <= BigDecimal(max), "Number out of range: $key")
        val result = n.decimal.toFloat()
        checkPack(result.isFinite() && (result != 0f || n.decimal.signum() == 0), "Unrepresentable number: $key")
        return result
    }
    fun color(key: String): Int {
        val s = string(key)
        checkPack(COLOR.matches(s), "Expected #AARRGGBB color: $key")
        return s.substring(1).toLong(16).toInt()
    }
    fun displayText(key: String, max: Int, allowEmpty: Boolean = false): String {
        val s = string(key)
        checkPack(s.codePointCount(0, s.length) <= max && (allowEmpty || s.isNotBlank()), "Invalid text length: $key")
        var index = 0
        while (index < s.length) {
            val point = s.codePointAt(index)
            val type = Character.getType(point)
            checkPack(!Character.isISOControl(point) && type != Character.FORMAT.toInt() &&
                type != Character.LINE_SEPARATOR.toInt() && type != Character.PARAGRAPH_SEPARATOR.toInt(), "Invalid display control: $key")
            index += Character.charCount(point)
        }
        checkPack(s.none { it == '<' || it == '>' || it.code == 96 } && !MARKUP_LINK.containsMatchIn(s) &&
            !LINK.containsMatchIn(s) && !BARE_LINK.containsMatchIn(s) && !s.contains("**") && !s.contains("__"),
            "Display text must not contain markup or links: $key")
        return s
    }
    fun optionalDisplayText(key: String, max: Int): String? =
        if (values.containsKey(key)) displayText(key, max, allowEmpty = true) else null
    companion object {
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
        private val COLOR = Regex("#[0-9A-Fa-f]{8}")
        private val MARKUP_LINK = Regex("\\[[^]]*]\\s*\\(")
        private val LINK = Regex("(?i)(\\b[a-z][a-z0-9+.-]*://|\\b(?:mailto|data|javascript|file|content|tel):|\\bwww\\.)")
        private val BARE_LINK = Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}(?:[/:?#\\s]|$)")
    }
}

/** Small strict JSON reader shared by JVM and Android; resource checks precede recursion/allocation. */
private class BoundedPackJson(private val source: String) {
    private var offset = 0
    private var values = 0
    fun read(): Any? {
        val result = value(0)
        whitespace()
        checkPack(offset == source.length, "Trailing JSON data")
        return result
    }
    private fun value(depth: Int): Any? {
        checkPack(depth <= FacePackLimits.MAX_JSON_DEPTH && ++values <= FacePackLimits.MAX_JSON_VALUES, "Manifest complexity limit")
        whitespace()
        checkPack(offset < source.length, "Truncated JSON value")
        return when (source[offset]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw FacePackException("Invalid JSON value")
        }
    }
    private fun objectValue(depth: Int): Map<String, Any?> {
        offset++
        val result = linkedMapOf<String, Any?>()
        whitespace()
        if (consume('}')) return result
        while (true) {
            whitespace()
            checkPack(offset < source.length && source[offset] == '"', "Expected JSON object key")
            val key = stringValue()
            checkPack(!result.containsKey(key), "Duplicate JSON field")
            whitespace(); expect(':')
            result[key] = value(depth + 1)
            whitespace()
            if (consume('}')) return result
            expect(',')
        }
    }
    private fun arrayValue(depth: Int): List<Any?> {
        offset++
        val result = arrayListOf<Any?>()
        whitespace()
        if (consume(']')) return result
        while (true) {
            result.add(value(depth + 1))
            whitespace()
            if (consume(']')) return result
            expect(',')
        }
    }
    private fun stringValue(): String {
        expect('"')
        val result = StringBuilder()
        var closed = false
        while (offset < source.length) {
            val c = source[offset++]
            if (c == '"') { closed = true; break }
            checkPack(c >= ' ', "Unescaped JSON control character")
            if (c != '\\') result.append(c) else {
                checkPack(offset < source.length, "Truncated JSON escape")
                when (val escaped = source[offset++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b'); 'f' -> result.append('\u000C')
                    'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                    'u' -> {
                        checkPack(offset + 4 <= source.length, "Truncated Unicode escape")
                        var code = 0
                        repeat(4) {
                            val digit = when (val hex = source[offset++]) {
                                in '0'..'9' -> hex - '0'; in 'a'..'f' -> hex - 'a' + 10; in 'A'..'F' -> hex - 'A' + 10
                                else -> throw FacePackException("Invalid Unicode escape")
                            }
                            code = (code shl 4) or digit
                        }
                        result.append(code.toChar())
                    }
                    else -> throw FacePackException("Invalid JSON escape")
                }
            }
        }
        checkPack(closed, "Unterminated JSON string")
        var index = 0
        while (index < result.length) {
            val c = result[index++]
            if (c.isHighSurrogate()) {
                checkPack(index < result.length && result[index].isLowSurrogate(), "Unpaired Unicode surrogate")
                index++
            } else checkPack(!c.isLowSurrogate(), "Unpaired Unicode surrogate")
        }
        return result.toString()
    }
    private fun numberValue(): PackNumber {
        val start = offset
        consume('-')
        checkPack(offset < source.length, "Truncated JSON number")
        if (!consume('0')) {
            checkPack(source[offset] in '1'..'9', "Invalid JSON number")
            digits()
        }
        if (consume('.')) { checkPack(offset < source.length && source[offset] in '0'..'9', "Invalid JSON fraction"); digits() }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            checkPack(offset < source.length && source[offset] in '0'..'9', "Invalid JSON exponent")
            digits()
        }
        checkPack(offset - start <= 64, "JSON number is too long")
        val text = source.substring(start, offset)
        val decimal = try { BigDecimal(text) } catch (error: NumberFormatException) { throw FacePackException("Invalid JSON number", error) }
        val number = decimal.toDouble()
        checkPack(number.isFinite() && (number != 0.0 || decimal.signum() == 0), "Nonfinite or unrepresentable JSON number")
        return PackNumber(text, decimal)
    }
    private fun digits() { while (offset < source.length && source[offset] in '0'..'9') offset++ }
    private fun literal(text: String, result: Any?): Any? {
        checkPack(source.startsWith(text, offset), "Invalid JSON literal")
        offset += text.length
        return result
    }
    private fun whitespace() { while (offset < source.length && source[offset] in " \t\r\n") offset++ }
    private fun consume(c: Char): Boolean = if (offset < source.length && source[offset] == c) { offset++; true } else false
    private fun expect(c: Char) { checkPack(consume(c), "Invalid JSON delimiter") }
}
