package ai.nanosearch.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawn icons for well-known apps. Each one is our own drawing of what the app is about (an onion for the onion browser, a shield for the private
 * browser, a paper plane for the plane messenger) or a plain letter mark, in a colour that suggests the app, standing on the same gem as every other icon.
 * Nothing here copies a logo: the point is that an app is recognisable at a glance, not that it is reproduced.
 *
 * An app is found by its package name, so a rename or a translated label changes nothing. Apps not listed here fall back to the category icons
 * (a handset, a camera, a calendar...) and then to their own picture on a tinted tile.
 */
object BrandIcons {
    class Brand(val id: String, val emblem: String, val top: Long, val bottom: Long, val text: String = "")

    private fun b(emblem: String, top: Long, bottom: Long, text: String = "") = Brand("$emblem|$top|$text", emblem, top, bottom, text)

    // colours that suggest an app without shouting: light end, deep end
    private val EMBER = b("shield", 0xFFFF8A3D, 0xFFD2310C)
    private val ONION = b("onion", 0xFFC59AFF, 0xFF5B2AA6)
    private val FLAME = b("flame", 0xFFFFC53D, 0xFFE0344B)
    private val RING_RED = b("ring", 0xFFFF6B6B, 0xFFB3122B)
    private val RING_BLUE = b("ring", 0xFF6FD0FF, 0xFF1560C0)
    private val RING_ORANGE = b("ring", 0xFFFFC857, 0xFFE06A12)
    private val WAVE_BLUE = b("wave", 0xFF63E0FF, 0xFF1D6FD8)
    private val PLANE = b("plane", 0xFF7CCBFF, 0xFF1F8FDB)
    private val CHAT_GREEN = b("chatphone", 0xFF6BE58A, 0xFF138A3F)
    private val CHAT_BLUE = b("chatdots", 0xFF6FA8FF, 0xFF2147C9)
    private val CHAT_PURPLE = b("chatdots", 0xFFB48CFF, 0xFF5A2FD0)
    private val CHAT_PINK = b("chatdots", 0xFFFF8FB5, 0xFFC4246B)
    private val CHAT_TEAL = b("chatdots", 0xFF6FE3D2, 0xFF0F8C84)
    private val MAIL_RED = b("mail", 0xFFFF7A6B, 0xFFC3261B)
    private val MAIL_BLUE = b("mail", 0xFF7FB6FF, 0xFF2158C4)
    private val MAIL_GREEN = b("mail", 0xFF7BE3A1, 0xFF18874D)
    private val MAIL_PURPLE = b("mail", 0xFFC09CFF, 0xFF5B31B5)
    private val FRAME = b("frame", 0xFFFFC857, 0xFFD3216B)
    private val PLAY_RED = b("play", 0xFFFF6B5E, 0xFFC4150E)
    private val PLAY_ORANGE = b("play", 0xFFFFB45C, 0xFFD9560B)
    private val PLAY_BLUE = b("play", 0xFF7FC4FF, 0xFF1D5FC4)
    private val NOTE_GREEN = b("arcs", 0xFF5CF08A, 0xFF129A44)
    private val BARS = b("bars", 0xFFFFB35C, 0xFFE0580F)
    private val PHONES = b("headphones", 0xFFFFB35C, 0xFFD9560B)
    private val CLOUD_BLUE = b("cloud", 0xFF9BDCFF, 0xFF2C7BD4)
    private val CLOUD_GREEN = b("cloud", 0xFF8BE8B0, 0xFF1F9B58)
    private val CLOUD_PURPLE = b("cloud", 0xFFC9A5FF, 0xFF6234C4)
    private val DOC_BLUE = b("page", 0xFF8FC3FF, 0xFF2461C9, "lines")
    private val SHEET_GREEN = b("page", 0xFF8BE8B0, 0xFF1E8E4F, "grid")
    private val SLIDE_YELLOW = b("page", 0xFFFFE07A, 0xFFE59A0B, "slide")
    private val DOC_RED = b("page", 0xFFFF8C80, 0xFFC02418, "lines")
    private val DOC_ORANGE = b("page", 0xFFFFC27A, 0xFFD2630B, "lines")
    private val CART = b("cart", 0xFFFFC857, 0xFFE0780F)
    private val BAG = b("bag", 0xFFFF8FB5, 0xFFC4246B)
    private val FOOD = b("fork", 0xFFFF9B6B, 0xFFD2380F)
    private val CAR = b("car", 0xFF8FA5B8, 0xFF2D3E50)
    private val COIN = b("coin", 0xFFFFD36B, 0xFFCC7A0A, "₹")
    private val COIN_PURPLE = b("coin", 0xFFC9A5FF, 0xFF5A2FC0, "₹")
    private val LOCK_GREEN = b("shieldlock", 0xFF74E6A4, 0xFF138A55)
    private val LOCK_PURPLE = b("shieldlock", 0xFFB997FF, 0xFF5B2FC0)
    private val LOCK_BLUE = b("shieldlock", 0xFF7CB8FF, 0xFF1F5BC9)
    private val LOCK_RED = b("shieldlock", 0xFFFF8478, 0xFFC02A1B)
    private val LOCK_ORANGE = b("shieldlock", 0xFFFFC56B, 0xFFD2660B)
    private val KEY = b("key", 0xFF9BCBFF, 0xFF2B64C4)
    private val PAD = b("controller", 0xFFB6BDC7, 0xFF565F6B)
    private val PAD_PURPLE = b("controller", 0xFFB7A0FF, 0xFF5A3FD0)
    private val SPARK_ORANGE = b("spark", 0xFFFFB07A, 0xFFD8571C)
    private val SPARK_GREEN = b("spark", 0xFF7CE6B5, 0xFF148A62)
    private val SPARK_BLUE = b("spark", 0xFF8CB8FF, 0xFF3A5FE0)
    private val TERM = b("terminal", 0xFF6FE88C, 0xFF14873A)
    private val DOWN = b("download", 0xFF7CE3A0, 0xFF138A52)
    private val SCAN = b("scan", 0xFF8FD0FF, 0xFF2467C9)
    private val VIDEOCALL_BLUE = b("videocall", 0xFF74B8FF, 0xFF2250C8)
    private val VIDEOCALL_GREEN = b("videocall", 0xFF74E6A4, 0xFF138A55)
    private val GHOST = b("ghost", 0xFFFFF27A, 0xFFE8B80B)
    private val PIN = b("pinmark", 0xFFFF7F7F, 0xFFC4202A)
    private val MUSIC_PINK = b("arcs", 0xFFFF8FA3, 0xFFC3185A)
    private val SHOP_BLUE = b("cart", 0xFF7FB6FF, 0xFF1F5BC9)
    private val GLOBE_RED = b("ring", 0xFFFF8C80, 0xFFC02418)

    private fun letter(text: String, top: Long, bottom: Long) = b("letter", top, bottom, text)

    private val PRESETS: Map<String, Brand> = mapOf(
        "EMBER" to EMBER,
        "ONION" to ONION,
        "FLAME" to FLAME,
        "RING_RED" to RING_RED,
        "RING_BLUE" to RING_BLUE,
        "RING_ORANGE" to RING_ORANGE,
        "WAVE_BLUE" to WAVE_BLUE,
        "PLANE" to PLANE,
        "CHAT_GREEN" to CHAT_GREEN,
        "CHAT_BLUE" to CHAT_BLUE,
        "CHAT_PURPLE" to CHAT_PURPLE,
        "CHAT_PINK" to CHAT_PINK,
        "CHAT_TEAL" to CHAT_TEAL,
        "MAIL_RED" to MAIL_RED,
        "MAIL_BLUE" to MAIL_BLUE,
        "MAIL_GREEN" to MAIL_GREEN,
        "MAIL_PURPLE" to MAIL_PURPLE,
        "FRAME" to FRAME,
        "PLAY_RED" to PLAY_RED,
        "PLAY_ORANGE" to PLAY_ORANGE,
        "PLAY_BLUE" to PLAY_BLUE,
        "NOTE_GREEN" to NOTE_GREEN,
        "BARS" to BARS,
        "PHONES" to PHONES,
        "CLOUD_BLUE" to CLOUD_BLUE,
        "CLOUD_GREEN" to CLOUD_GREEN,
        "CLOUD_PURPLE" to CLOUD_PURPLE,
        "DOC_BLUE" to DOC_BLUE,
        "SHEET_GREEN" to SHEET_GREEN,
        "SLIDE_YELLOW" to SLIDE_YELLOW,
        "DOC_RED" to DOC_RED,
        "DOC_ORANGE" to DOC_ORANGE,
        "CART" to CART,
        "BAG" to BAG,
        "FOOD" to FOOD,
        "CAR" to CAR,
        "COIN" to COIN,
        "COIN_PURPLE" to COIN_PURPLE,
        "LOCK_GREEN" to LOCK_GREEN,
        "LOCK_PURPLE" to LOCK_PURPLE,
        "LOCK_BLUE" to LOCK_BLUE,
        "LOCK_RED" to LOCK_RED,
        "LOCK_ORANGE" to LOCK_ORANGE,
        "KEY" to KEY,
        "PAD" to PAD,
        "PAD_PURPLE" to PAD_PURPLE,
        "SPARK_ORANGE" to SPARK_ORANGE,
        "SPARK_GREEN" to SPARK_GREEN,
        "SPARK_BLUE" to SPARK_BLUE,
        "TERM" to TERM,
        "DOWN" to DOWN,
        "SCAN" to SCAN,
        "VIDEOCALL_BLUE" to VIDEOCALL_BLUE,
        "VIDEOCALL_GREEN" to VIDEOCALL_GREEN,
        "GHOST" to GHOST,
        "PIN" to PIN,
        "MUSIC_PINK" to MUSIC_PINK,
        "SHOP_BLUE" to SHOP_BLUE,
        "GLOBE_RED" to GLOBE_RED,
    )

    private class Rule(val re: Regex, val brand: Brand)

    /**
     * Which app gets which drawing is data, not code: one line per pattern in `assets/brand_icons.tsv` (a package-name regex, a tab, a drawing), and an
     * optional `brand_icons.tsv` in the launcher's files folder with the user's own lines, read first. A new well-known app is one more line; nothing here changes.
     * The first matching line wins, so specific lines sit above broad ones.
     */
    @Volatile private var rules: List<Rule>? = null

    private fun rules(context: Context): List<Rule> = rules ?: synchronized(this) {
        rules ?: (readRules(java.io.File(context.filesDir, "brand_icons.tsv").takeIf { it.isFile }?.readLines().orEmpty()) +
            readRules(runCatching { context.assets.open("brand_icons.tsv").bufferedReader().readLines() }.getOrDefault(emptyList()))).also { rules = it }
    }

    /** Forget what was read, so an edited extras file is picked up (the icon cache must be cleared with it). */
    fun reload() { rules = null }

    private fun readRules(lines: List<String>): List<Rule> = lines.mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val cols = line.split('\t')
        if (cols.size < 2) return@mapNotNull null
        val brand = parseBrand(cols[1].trim()) ?: return@mapNotNull null
        runCatching { Rule(Regex(cols[0].trim(), RegexOption.IGNORE_CASE), brand) }.getOrNull() // a bad pattern skips its own line only
    }

    /** `PRESET_NAME`, or `emblem:text:TOPHEX:BOTTOMHEX` (also `letter:X:TOPHEX:BOTTOMHEX`, where the text is the second part). */
    private fun parseBrand(spec: String): Brand? {
        PRESETS[spec]?.let { return it }
        val p = spec.split(':')
        if (p.size < 4) return null
        val top = p[p.size - 2].toLongOrNull(16) ?: return null
        val bottom = p[p.size - 1].toLongOrNull(16) ?: return null
        val pad = { v: Long -> if (v > 0xFFFFFFL) v else 0xFF000000L or v }
        return if (p[0] == "letter") letter(p[1], pad(top), pad(bottom)) else b(p[0], pad(top), pad(bottom), p[1])
    }

    /** Every distinct drawing, in table order: for checking them all at a glance. */
    internal fun all(context: Context): List<Brand> = rules(context).map { it.brand }.distinctBy { it.id }

    /** The brand drawing for [pkg], or null when the app is not one of the known ones. */
    fun find(context: Context, pkg: String): Brand? = rules(context).firstOrNull { it.re.containsMatchIn(pkg) }?.brand

    /** The drawing for games the package list does not know: any app Android itself files under games. */
    val GAME: Brand = PAD

    /** The gem colour for [brand]: a muted tint of its colour, so the grid stays graphite with a hint of hue, never a loud tile. */
    fun tint(brand: Brand): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(brand.bottom.toInt(), hsv)
        hsv[1] = (hsv[1] * 0.42f).coerceIn(0.14f, 0.38f); hsv[2] = hsv[2].coerceIn(0.34f, 0.5f)
        return Color.HSVToColor(hsv)
    }

    // ---------------------------------------------------------------- drawing

    fun draw(brand: Brand): Bitmap = AppIcons.run { bitmap { c -> paintEmblem(c, brand) } }

    private fun AppIcons.paintEmblem(c: Canvas, b: Brand) {
        val top = b.top.toInt(); val bot = b.bottom.toInt()
        when (b.emblem) {
            "shield" -> shield(c, top, bot, lock = false)
            "shieldlock" -> shield(c, top, bot, lock = true)
            "onion" -> onion(c, top, bot)
            "flame" -> flame(c, top, bot)
            "ring" -> ring(c, top, bot)
            "wave" -> wave(c, top, bot)
            "plane" -> plane(c, top, bot)
            "chatphone" -> chatPhone(c, top, bot)
            "chatdots" -> chatDots(c, top, bot)
            "mail" -> mail(c, top, bot)
            "frame" -> frame(c, top, bot)
            "play" -> play(c, top, bot)
            "arcs" -> arcs(c, top, bot)
            "bars" -> bars(c, top, bot)
            "headphones" -> headphones(c, top, bot)
            "cloud" -> cloud(c, top, bot)
            "page" -> page(c, top, bot, b.text)
            "cart" -> cart(c, top, bot)
            "bag" -> bag(c, top, bot)
            "fork" -> fork(c, top, bot)
            "car" -> car(c, top, bot)
            "coin" -> coin(c, top, bot, b.text)
            "key" -> key(c, top, bot)
            "controller" -> controller(c, top, bot)
            "spark" -> spark(c, top, bot)
            "terminal" -> terminal(c, top, bot)
            "download" -> download(c, top, bot)
            "scan" -> scan(c, top, bot)
            "videocall" -> videoCall(c, top, bot)
            "ghost" -> ghost(c, top, bot)
            "pinmark" -> pinMark(c, top, bot)
            "alien" -> alien(c, top, bot)
            "gallerymark" -> galleryMark(c, top, bot)
            "heart" -> heart(c, top, bot)
            "book" -> book(c, top, bot)
            "note" -> note(c, top, bot)
            "letter" -> letterMark(c, top, bot, b.text)
            else -> letterMark(c, top, bot, "?")
        }
    }

    private val WHITE = 0xFFFFFFFF.toInt()
    private val SOFT = 0xFFE8EEF5.toInt()

    /** A solid shape with a lit upper half, the common starting point. */
    private fun AppIcons.solid(c: Canvas, path: Path, top: Int, bot: Int, y0: Float = 20f, y1: Float = 176f) {
        c.glossy(path, top, bot, y0, y1); c.sheen(path, y0, y0 + (y1 - y0) * 0.55f, 0x55)
    }

    private fun AppIcons.stroke(c: Canvas, path: Path, w: Float, color: Int, cap: Paint.Cap = Paint.Cap.ROUND) {
        c.drawPath(path, paint().apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = cap; strokeJoin = Paint.Join.ROUND; this.color = color })
    }

    // a shield: the private browser (with a crest) and every VPN (with a padlock)
    private fun AppIcons.shield(c: Canvas, top: Int, bot: Int, lock: Boolean) {
        val s = Path().apply {
            moveTo(96f, 18f); lineTo(160f, 40f); lineTo(156f, 104f)
            cubicTo(150f, 142f, 124f, 164f, 96f, 178f)
            cubicTo(68f, 164f, 42f, 142f, 36f, 104f); lineTo(32f, 40f); close()
        }
        solid(c, s, top, bot)
        if (lock) {
            c.drawRoundRect(RectF(68f, 86f, 124f, 130f), 10f, 10f, paint().apply { color = WHITE })
            stroke(c, Path().apply { moveTo(77f, 88f); lineTo(77f, 74f); cubicTo(77f, 52f, 115f, 52f, 115f, 74f); lineTo(115f, 88f) }, 9f, WHITE)
            c.drawCircle(96f, 106f, 6f, paint().apply { color = bot })
        } else { // a faceted crest: an inner shield and a bright spine
            val inner = Path().apply { moveTo(96f, 46f); lineTo(136f, 60f); lineTo(133f, 100f); cubicTo(129f, 124f, 112f, 138f, 96f, 148f); cubicTo(80f, 138f, 63f, 124f, 59f, 100f); lineTo(56f, 60f); close() }
            c.drawPath(inner, paint().apply { color = 0x55FFFFFF })
            c.drawPath(Path().apply { moveTo(96f, 56f); lineTo(118f, 100f); lineTo(96f, 140f); lineTo(74f, 100f); close() }, paint().apply { color = WHITE })
            c.drawPath(Path().apply { moveTo(96f, 56f); lineTo(118f, 100f); lineTo(96f, 100f); close() }, paint().apply { color = 0x33000000 })
        }
    }

    // an onion with its layers and a sprout
    private fun AppIcons.onion(c: Canvas, top: Int, bot: Int) {
        val bulb = Path().apply {
            moveTo(96f, 30f); cubicTo(112f, 62f, 168f, 70f, 160f, 116f)
            cubicTo(154f, 156f, 122f, 172f, 96f, 172f)
            cubicTo(70f, 172f, 38f, 156f, 32f, 116f)
            cubicTo(24f, 70f, 80f, 62f, 96f, 30f); close()
        }
        solid(c, bulb, top, bot, 30f, 172f)
        val line = 0x66FFFFFF
        stroke(c, Path().apply { moveTo(96f, 40f); cubicTo(60f, 80f, 56f, 140f, 96f, 168f) }, 5f, line)
        stroke(c, Path().apply { moveTo(96f, 40f); cubicTo(132f, 80f, 136f, 140f, 96f, 168f) }, 5f, line)
        stroke(c, Path().apply { moveTo(96f, 40f); cubicTo(88f, 90f, 88f, 130f, 96f, 168f) }, 4f, 0x44FFFFFF)
        val leaf = Path().apply { moveTo(96f, 34f); cubicTo(92f, 20f, 112f, 10f, 128f, 16f); cubicTo(122f, 32f, 108f, 40f, 96f, 34f); close() }
        solid(c, leaf, 0xFF9BE86B.toInt(), 0xFF2E8B2A.toInt(), 10f, 40f)
    }

    // a swirl of flame around a core
    private fun AppIcons.flame(c: Canvas, top: Int, bot: Int) {
        val orb = Path().apply { addCircle(96f, 100f, 70f, Path.Direction.CW) }
        c.shadowOf(orb)
        c.drawCircle(96f, 100f, 70f, paint().apply { shader = RadialGradient(76f, 70f, 110f, intArrayOf(top, 0xFFFF7A3B.toInt(), bot), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
        val tail = Path().apply { moveTo(150f, 40f); cubicTo(182f, 56f, 186f, 104f, 160f, 134f); cubicTo(170f, 100f, 156f, 70f, 126f, 56f); close() }
        c.drawPath(tail, paint().apply { shader = lin(40f, 134f, top, bot) })
        c.drawCircle(96f, 100f, 40f, paint().apply { shader = RadialGradient(88f, 88f, 50f, intArrayOf(0xFF7B3FD0.toInt(), 0xFF2D1670.toInt()), null, Shader.TileMode.CLAMP) })
        c.drawCircle(80f, 84f, 12f, paint().apply { color = 0x55FFFFFF })
    }

    // a thick glossy ring, for browsers named after a letter or a circle
    private fun AppIcons.ring(c: Canvas, top: Int, bot: Int) {
        c.drawCircle(96f, 104f, 70f, paint().apply { color = 0x70000000; maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(96f, 98f, 62f, paint().apply { style = Paint.Style.STROKE; strokeWidth = 36f; shader = lin(36f, 160f, top, bot) })
        c.drawCircle(96f, 98f, 44f, paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x66FFFFFF })
        c.drawArc(RectF(40f, 42f, 152f, 154f), 200f, 80f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = 0x88FFFFFF.toInt() })
        c.drawCircle(96f, 98f, 34f, paint().apply { shader = lin(64f, 132f, WHITE, 0xFFC9D2DC.toInt()) })
        c.drawCircle(96f, 98f, 17f, paint().apply { shader = lin(81f, 115f, top, bot) })
        c.drawCircle(90f, 91f, 6f, paint().apply { color = 0x88FFFFFF.toInt() })
    }

    // a wave across a disc
    private fun AppIcons.wave(c: Canvas, top: Int, bot: Int) {
        val d = Path().apply { addCircle(96f, 98f, 70f, Path.Direction.CW) }
        solid(c, d, top, bot, 28f, 168f)
        stroke(c, Path().apply { moveTo(40f, 112f); cubicTo(64f, 70f, 90f, 150f, 112f, 100f); cubicTo(126f, 70f, 140f, 96f, 152f, 84f) }, 14f, WHITE)
    }

    // a paper plane
    private fun AppIcons.plane(c: Canvas, top: Int, bot: Int) {
        val p = Path().apply { moveTo(22f, 92f); lineTo(172f, 28f); lineTo(140f, 164f); lineTo(100f, 124f); lineTo(76f, 154f); lineTo(74f, 112f); close() }
        c.glossy(p, WHITE, 0xFFC2D2E2.toInt(), 28f, 164f)
        c.drawPath(Path().apply { moveTo(74f, 112f); lineTo(172f, 28f); lineTo(100f, 124f); close() }, paint().apply { shader = lin(28f, 124f, top, bot) })
        c.drawPath(Path().apply { moveTo(76f, 154f); lineTo(74f, 112f); lineTo(100f, 124f); close() }, paint().apply { color = 0x33000000 })
    }

    // a round chat bubble with a handset
    private fun AppIcons.chatPhone(c: Canvas, top: Int, bot: Int) {
        val bubble = Path().apply { addCircle(96f, 92f, 68f, Path.Direction.CW); moveTo(46f, 128f); lineTo(30f, 172f); lineTo(80f, 156f); close() }
        solid(c, bubble, top, bot, 24f, 172f)
        val h = Path().apply { moveTo(66f, 66f); cubicTo(64f, 98f, 88f, 126f, 122f, 126f) }
        stroke(c, h, 22f, WHITE)
        c.drawRoundRect(RectF(56f, 56f, 80f, 82f), 8f, 8f, paint().apply { color = WHITE })
        c.drawRoundRect(RectF(108f, 110f, 134f, 136f), 8f, 8f, paint().apply { color = WHITE })
    }

    // a rounded speech bubble with three dots
    private fun AppIcons.chatDots(c: Canvas, top: Int, bot: Int) {
        val bubble = Path().apply { addRoundRect(RectF(20f, 30f, 172f, 138f), 40f, 40f, Path.Direction.CW); moveTo(54f, 128f); lineTo(44f, 170f); lineTo(94f, 138f); close() }
        solid(c, bubble, top, bot, 30f, 170f)
        for (i in 0..2) c.drawCircle(60f + i * 36f, 84f, 10f, paint().apply { color = WHITE })
    }

    // an envelope in a colour
    private fun AppIcons.mail(c: Canvas, top: Int, bot: Int) {
        val body = rr(18f, 42f, 174f, 150f, 16f)
        solid(c, body, top, bot, 42f, 150f)
        stroke(c, Path().apply { moveTo(26f, 54f); lineTo(96f, 108f); lineTo(166f, 54f) }, 9f, WHITE)
        stroke(c, Path().apply { moveTo(28f, 142f); lineTo(76f, 92f); moveTo(164f, 142f); lineTo(116f, 92f) }, 4f, 0x66FFFFFF)
    }

    // a rounded frame with a lens and a dot
    private fun AppIcons.frame(c: Canvas, top: Int, bot: Int) {
        val f = rr(24f, 28f, 168f, 172f, 44f)
        c.shadowOf(f)
        c.drawPath(f, paint().apply { shader = LinearGradientDiag(24f, 172f, 168f, 28f, top, 0xFFE0386B.toInt(), 0xFF7A3FD0.toInt()) })
        c.sheen(f, 28f, 100f, 0x44)
        c.drawCircle(96f, 100f, 34f, paint().apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = WHITE })
        c.drawCircle(136f, 60f, 8f, paint().apply { color = WHITE })
    }

    @Suppress("FunctionName")
    private fun LinearGradientDiag(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int, cc: Int) =
        android.graphics.LinearGradient(x0, y0, x1, y1, intArrayOf(a, b, cc), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)

    // a rounded screen with a play triangle
    private fun AppIcons.play(c: Canvas, top: Int, bot: Int) {
        val s = rr(16f, 46f, 176f, 146f, 38f)
        solid(c, s, top, bot, 46f, 146f)
        c.drawPath(Path().apply { moveTo(80f, 72f); lineTo(80f, 120f); lineTo(124f, 96f); close() }, paint().apply { color = WHITE })
    }

    // sound arcs over a disc
    private fun AppIcons.arcs(c: Canvas, top: Int, bot: Int) {
        val d = Path().apply { addCircle(96f, 98f, 72f, Path.Direction.CW) }
        solid(c, d, top, bot, 26f, 170f)
        val pen = 0xEE0B2B1A.toInt()
        stroke(c, Path().apply { moveTo(52f, 78f); cubicTo(88f, 64f, 126f, 70f, 148f, 88f) }, 14f, pen)
        stroke(c, Path().apply { moveTo(58f, 106f); cubicTo(88f, 96f, 120f, 100f, 138f, 114f) }, 12f, pen)
        stroke(c, Path().apply { moveTo(64f, 130f); cubicTo(88f, 124f, 112f, 126f, 128f, 138f) }, 10f, pen)
    }

    // equaliser bars
    private fun AppIcons.bars(c: Canvas, top: Int, bot: Int) {
        val hs = floatArrayOf(60f, 108f, 140f, 96f, 70f)
        for (i in hs.indices) {
            val x = 30f + i * 29f
            solid(c, rr(x, 150f - hs[i], x + 20f, 150f, 8f), top, bot, 150f - hs[i], 150f)
        }
    }

    // headphones
    private fun AppIcons.headphones(c: Canvas, top: Int, bot: Int) {
        val band = Path().apply { moveTo(34f, 112f); cubicTo(30f, 36f, 162f, 36f, 158f, 112f) }
        c.drawPath(band, paint().apply { style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND; color = 0x70000000; maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL) })
        stroke(c, band, 16f, SOFT)
        solid(c, rr(22f, 100f, 62f, 164f, 18f), top, bot, 100f, 164f)
        solid(c, rr(130f, 100f, 170f, 164f, 18f), top, bot, 100f, 164f)
    }

    // a cloud
    private fun AppIcons.cloud(c: Canvas, top: Int, bot: Int) {
        val p = Path().apply { addCircle(70f, 108f, 36f, Path.Direction.CW); addCircle(112f, 88f, 46f, Path.Direction.CW); addCircle(142f, 118f, 30f, Path.Direction.CW); addRoundRect(RectF(46f, 108f, 160f, 148f), 20f, 20f, Path.Direction.CW) }
        val u = Path(); u.op(p, p, Path.Op.UNION)
        solid(c, u, top, bot, 42f, 148f)
    }

    // a page with a folded corner and a mark: text lines, a grid or a slide
    private fun AppIcons.page(c: Canvas, top: Int, bot: Int, mark: String) {
        val p = Path().apply { moveTo(40f, 20f); lineTo(118f, 20f); lineTo(156f, 58f); lineTo(156f, 172f); lineTo(40f, 172f); close() }
        solid(c, p, top, bot, 20f, 172f)
        c.drawPath(Path().apply { moveTo(118f, 20f); lineTo(118f, 58f); lineTo(156f, 58f); close() }, paint().apply { color = 0x66FFFFFF })
        when (mark) {
            "grid" -> { val w = paint().apply { color = WHITE }
                for (r in 0..2) for (k in 0..1) c.drawRoundRect(RectF(58f + k * 46f, 80f + r * 28f, 58f + k * 46f + 38f, 80f + r * 28f + 22f), 4f, 4f, w) }
            "slide" -> { c.drawRoundRect(RectF(56f, 82f, 140f, 140f), 8f, 8f, paint().apply { color = WHITE }); c.drawRoundRect(RectF(68f, 94f, 128f, 128f), 4f, 4f, paint().apply { color = bot }) }
            else -> { val w = paint().apply { color = WHITE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(58f, 84f, 138f, 84f, w); c.drawLine(58f, 108f, 138f, 108f, w); c.drawLine(58f, 132f, 108f, 132f, w) }
        }
    }

    // a shopping basket
    private fun AppIcons.cart(c: Canvas, top: Int, bot: Int) {
        stroke(c, Path().apply { moveTo(20f, 40f); lineTo(46f, 40f); lineTo(70f, 128f); lineTo(146f, 128f) }, 12f, SOFT)
        val b = Path().apply { moveTo(52f, 62f); lineTo(172f, 62f); lineTo(152f, 118f); lineTo(70f, 118f); close() }
        solid(c, b, top, bot, 62f, 118f)
        c.drawCircle(84f, 150f, 14f, paint().apply { color = SOFT }); c.drawCircle(140f, 150f, 14f, paint().apply { color = SOFT })
    }

    // a shopping bag
    private fun AppIcons.bag(c: Canvas, top: Int, bot: Int) {
        stroke(c, Path().apply { moveTo(66f, 66f); cubicTo(66f, 20f, 130f, 20f, 130f, 66f) }, 12f, SOFT)
        solid(c, rr(36f, 62f, 156f, 168f, 18f), top, bot, 62f, 168f)
        c.drawCircle(74f, 88f, 7f, paint().apply { color = WHITE }); c.drawCircle(118f, 88f, 7f, paint().apply { color = WHITE })
    }

    // a fork and a spoon
    private fun AppIcons.fork(c: Canvas, top: Int, bot: Int) {
        val handle = paint().apply { shader = lin(60f, 172f, SOFT, 0xFF9AA4B0.toInt()) }
        c.drawRoundRect(RectF(52f, 80f, 68f, 172f), 8f, 8f, handle)
        for (i in 0..2) c.drawRoundRect(RectF(36f + i * 16f, 20f, 46f + i * 16f, 92f), 5f, 5f, handle)
        c.drawRoundRect(RectF(36f, 70f, 84f, 100f), 12f, 12f, handle)
        c.drawRoundRect(RectF(124f, 80f, 140f, 172f), 8f, 8f, handle)
        solid(c, Path().apply { addOval(RectF(106f, 20f, 158f, 100f), Path.Direction.CW) }, top, bot, 20f, 100f)
    }

    // a car seen from the side
    private fun AppIcons.car(c: Canvas, top: Int, bot: Int) {
        c.save(); c.scale(1.1f, 1.16f, 96f, 100f)
        val body = Path().apply { moveTo(20f, 128f); lineTo(20f, 104f); cubicTo(20f, 92f, 34f, 88f, 48f, 84f); lineTo(70f, 52f); cubicTo(74f, 46f, 80f, 44f, 88f, 44f); lineTo(118f, 44f); cubicTo(126f, 44f, 132f, 46f, 136f, 52f); lineTo(152f, 84f); cubicTo(166f, 88f, 174f, 94f, 174f, 108f); lineTo(174f, 128f); close() }
        solid(c, body, top, bot, 44f, 128f)
        c.drawPath(Path().apply { moveTo(78f, 84f); lineTo(90f, 58f); lineTo(116f, 58f); lineTo(126f, 84f); close() }, paint().apply { color = 0xCCE8F4FF.toInt() })
        c.drawCircle(56f, 132f, 20f, paint().apply { color = 0xFF14161A.toInt() }); c.drawCircle(56f, 132f, 9f, paint().apply { color = SOFT })
        c.drawCircle(140f, 132f, 20f, paint().apply { color = 0xFF14161A.toInt() }); c.drawCircle(140f, 132f, 9f, paint().apply { color = SOFT })
        c.restore()
    }

    // a coin with a currency sign
    private fun AppIcons.coin(c: Canvas, top: Int, bot: Int, sign: String) {
        solid(c, Path().apply { addCircle(96f, 98f, 72f, Path.Direction.CW) }, top, bot, 26f, 170f)
        c.drawCircle(96f, 98f, 56f, paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x66FFFFFF })
        c.drawText(sign, 96f, 130f, paint().apply { color = WHITE; textSize = 92f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD })
    }

    // a key
    private fun AppIcons.key(c: Canvas, top: Int, bot: Int) {
        val head = Path().apply { addCircle(66f, 70f, 42f, Path.Direction.CW) }
        val shaft = Path().apply { addRoundRect(RectF(84f, 84f, 164f, 108f), 10f, 10f, Path.Direction.CW); addRect(RectF(124f, 100f, 138f, 134f), Path.Direction.CW); addRect(RectF(148f, 100f, 162f, 124f), Path.Direction.CW) }
        c.save(); c.scale(1.16f, 1.16f, 96f, 98f); c.rotate(32f, 66f, 70f); c.translate(14f, 22f)
        solid(c, shaft, top, bot, 84f, 134f); solid(c, head, top, bot, 28f, 112f)
        c.drawCircle(58f, 62f, 12f, paint().apply { color = 0xFF1A1E24.toInt() })
        c.restore()
    }

    // a game controller
    private fun AppIcons.controller(c: Canvas, top: Int, bot: Int) {
        val body = Path().apply { moveTo(34f, 66f); cubicTo(58f, 56f, 134f, 56f, 158f, 66f); cubicTo(180f, 76f, 186f, 140f, 164f, 150f); cubicTo(150f, 156f, 138f, 142f, 130f, 128f); lineTo(62f, 128f); cubicTo(54f, 142f, 42f, 156f, 28f, 150f); cubicTo(6f, 140f, 12f, 76f, 34f, 66f); close() }
        solid(c, body, top, bot, 56f, 156f)
        val w = paint().apply { color = WHITE }
        c.drawRoundRect(RectF(48f, 86f, 84f, 98f), 5f, 5f, w); c.drawRoundRect(RectF(60f, 74f, 72f, 110f), 5f, 5f, w)
        c.drawCircle(128f, 82f, 8f, w); c.drawCircle(146f, 98f, 8f, w)
    }

    // four-pointed sparks, for the assistants
    private fun AppIcons.spark(c: Canvas, top: Int, bot: Int) {
        fun star(cx: Float, cy: Float, o: Float, i: Float) = Path().apply {
            for (k in 0 until 8) { val a = Math.toRadians(k * 45.0 - 90.0); val rad = if (k % 2 == 0) o else i
                val x = cx + (rad * cos(a)).toFloat(); val y = cy + (rad * sin(a)).toFloat(); if (k == 0) moveTo(x, y) else lineTo(x, y) }
            close()
        }
        solid(c, star(86f, 98f, 76f, 18f), top, bot, 22f, 174f)
        solid(c, star(146f, 52f, 30f, 8f), top, bot, 22f, 82f)
    }

    // a terminal prompt
    private fun AppIcons.terminal(c: Canvas, top: Int, bot: Int) {
        val s = rr(16f, 36f, 176f, 156f, 24f)
        c.glossy(s, 0xFF30363E.toInt(), 0xFF0E1114.toInt(), 36f, 156f); c.sheen(s, 36f, 96f, 0x33)
        stroke(c, Path().apply { moveTo(48f, 74f); lineTo(80f, 98f); lineTo(48f, 122f) }, 12f, top)
        stroke(c, Path().apply { moveTo(94f, 124f); lineTo(140f, 124f) }, 12f, bot)
    }

    // an arrow into a tray
    private fun AppIcons.download(c: Canvas, top: Int, bot: Int) {
        solid(c, rr(28f, 130f, 164f, 160f, 12f), SOFT, 0xFF9AA4B0.toInt(), 130f, 160f)
        val arrow = Path().apply { moveTo(80f, 24f); lineTo(112f, 24f); lineTo(112f, 74f); lineTo(142f, 74f); lineTo(96f, 126f); lineTo(50f, 74f); lineTo(80f, 74f); close() }
        solid(c, arrow, top, bot, 24f, 126f)
    }

    // a page held inside scanner corners
    private fun AppIcons.scan(c: Canvas, top: Int, bot: Int) {
        val k = Path().apply {
            moveTo(24f, 62f); lineTo(24f, 28f); lineTo(58f, 28f); moveTo(134f, 28f); lineTo(168f, 28f); lineTo(168f, 62f)
            moveTo(168f, 130f); lineTo(168f, 164f); lineTo(134f, 164f); moveTo(58f, 164f); lineTo(24f, 164f); lineTo(24f, 130f)
        }
        stroke(c, k, 12f, top)
        solid(c, rr(54f, 52f, 138f, 140f, 8f), WHITE, 0xFFC9D4E0.toInt(), 52f, 140f)
        val w = paint().apply { color = bot; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(68f, 76f, 124f, 76f, w); c.drawLine(68f, 94f, 124f, 94f, w); c.drawLine(68f, 112f, 102f, 112f, w)
    }

    // a camera and a call bubble
    private fun AppIcons.videoCall(c: Canvas, top: Int, bot: Int) {
        solid(c, rr(16f, 50f, 124f, 146f, 26f), top, bot, 50f, 146f)
        solid(c, Path().apply { moveTo(130f, 82f); lineTo(176f, 54f); lineTo(176f, 142f); lineTo(130f, 114f); close() }, top, bot, 54f, 142f)
        c.drawCircle(70f, 98f, 20f, paint().apply { color = WHITE })
    }

    // a friendly ghost
    private fun AppIcons.ghost(c: Canvas, top: Int, bot: Int) {
        val g = Path().apply {
            moveTo(40f, 154f); lineTo(40f, 90f); cubicTo(40f, 40f, 152f, 40f, 152f, 90f); lineTo(152f, 154f)
            lineTo(134f, 140f); lineTo(115f, 156f); lineTo(96f, 140f); lineTo(77f, 156f); lineTo(58f, 140f); close()
        }
        solid(c, g, top, bot, 40f, 156f)
        c.drawCircle(76f, 94f, 9f, paint().apply { color = 0xFF222222.toInt() }); c.drawCircle(116f, 94f, 9f, paint().apply { color = 0xFF222222.toInt() })
    }

    // a map pin
    private fun AppIcons.pinMark(c: Canvas, top: Int, bot: Int) {
        val p = Path().apply { moveTo(96f, 176f); cubicTo(60f, 130f, 34f, 108f, 34f, 78f); cubicTo(34f, 42f, 62f, 20f, 96f, 20f); cubicTo(130f, 20f, 158f, 42f, 158f, 78f); cubicTo(158f, 108f, 132f, 130f, 96f, 176f); close() }
        solid(c, p, top, bot, 20f, 176f)
        c.drawCircle(96f, 78f, 22f, paint().apply { color = WHITE })
    }

    // a round face with an antenna
    private fun AppIcons.alien(c: Canvas, top: Int, bot: Int) {
        stroke(c, Path().apply { moveTo(96f, 54f); lineTo(118f, 26f) }, 8f, SOFT)
        c.drawCircle(124f, 24f, 12f, paint().apply { color = WHITE })
        solid(c, Path().apply { addOval(RectF(24f, 54f, 168f, 160f), Path.Direction.CW) }, top, bot, 54f, 160f)
        c.drawCircle(70f, 104f, 14f, paint().apply { color = WHITE }); c.drawCircle(122f, 104f, 14f, paint().apply { color = WHITE })
        stroke(c, Path().apply { moveTo(68f, 130f); cubicTo(86f, 146f, 106f, 146f, 124f, 130f) }, 7f, WHITE)
    }

    // a pinwheel of four petals, for a photo library
    private fun AppIcons.galleryMark(c: Canvas, top: Int, bot: Int) {
        val cols = intArrayOf(0xFFFF7A59.toInt(), 0xFFFFC73D.toInt(), 0xFF3DD28A.toInt(), 0xFF4A8CFF.toInt())
        for (i in 0 until 4) {
            c.save(); c.rotate(i * 90f, 96f, 98f)
            val petal = Path().apply { moveTo(96f, 98f); cubicTo(96f, 40f, 148f, 34f, 148f, 66f); cubicTo(148f, 90f, 124f, 98f, 96f, 98f); close() }
            c.drawPath(petal, paint().apply { shader = lin(34f, 98f, cols[i], ai.nanosearch.launcher.ui.SpiritGlass.darken(cols[i], 0.25f)) })
            c.restore()
        }
        c.drawCircle(96f, 98f, 10f, paint().apply { color = WHITE })
    }

    // a heart with a pulse line, for fitness and health
    private fun AppIcons.heart(c: Canvas, top: Int, bot: Int) {
        val h = Path().apply { moveTo(96f, 172f); cubicTo(26f, 120f, 18f, 70f, 52f, 44f); cubicTo(76f, 26f, 96f, 44f, 96f, 62f); cubicTo(96f, 44f, 116f, 26f, 140f, 44f); cubicTo(174f, 70f, 166f, 120f, 96f, 172f); close() }
        solid(c, h, top, bot, 30f, 172f)
        stroke(c, Path().apply { moveTo(44f, 100f); lineTo(76f, 100f); lineTo(90f, 70f); lineTo(106f, 128f); lineTo(118f, 100f); lineTo(148f, 100f) }, 9f, WHITE)
    }

    // an open book, for readers and learning apps
    private fun AppIcons.book(c: Canvas, top: Int, bot: Int) {
        val left = Path().apply { moveTo(96f, 54f); cubicTo(72f, 38f, 42f, 38f, 20f, 48f); lineTo(20f, 150f); cubicTo(42f, 140f, 72f, 140f, 96f, 156f); close() }
        val right = Path().apply { moveTo(96f, 54f); cubicTo(120f, 38f, 150f, 38f, 172f, 48f); lineTo(172f, 150f); cubicTo(150f, 140f, 120f, 140f, 96f, 156f); close() }
        solid(c, left, top, bot, 38f, 156f); solid(c, right, top, bot, 38f, 156f)
        val pen = paint().apply { color = 0x66FFFFFF; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        for (i in 0..2) { c.drawLine(36f, 76f + i * 20f, 80f, 82f + i * 20f, pen); c.drawLine(112f, 82f + i * 20f, 156f, 76f + i * 20f, pen) }
        c.drawLine(96f, 54f, 96f, 156f, paint().apply { color = 0x66000000; strokeWidth = 3f })
    }

    // a bold letter, for apps whose whole mark is one: fattened, outlined and shaded so the gem's gloss cannot wash it out
    private fun AppIcons.letterMark(c: Canvas, top: Int, bot: Int, text: String) {
        val size = if (text.length > 1) 112f else 140f
        val p = paint().apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val y = 98f + (-p.fontMetrics.ascent - p.fontMetrics.descent) / 2f
        c.drawText(text, 96f, y + 7f, p.apply { color = 0x88000000.toInt(); maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) })
        p.maskFilter = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.strokeJoin = Paint.Join.ROUND; p.color = 0xE60E1116.toInt()
        c.drawText(text, 96f, y, p)
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 4f
        p.shader = lin(98f - size / 2f, 98f + size / 2f, ai.nanosearch.launcher.ui.SpiritGlass.darken(top, 0.0f), ai.nanosearch.launcher.ui.SpiritGlass.darken(bot, 0.1f))
        c.drawText(text, 96f, y, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f; p.color = 0x77FFFFFF
        c.drawText(text, 96f, y, p)
    }

    // a note with split-colour echoes
    private fun AppIcons.note(c: Canvas, top: Int, bot: Int) {
        fun shape(dx: Float, dy: Float) = Path().apply {
            addCircle(66f + dx, 140f + dy, 28f, Path.Direction.CW)
            addRect(RectF(86f + dx, 34f + dy, 106f + dx, 140f + dy), Path.Direction.CW)
            moveTo(106f + dx, 34f + dy); cubicTo(106f + dx, 80f + dy, 144f + dx, 66f + dy, 162f + dx, 104f + dy)
            lineTo(162f + dx, 130f + dy); cubicTo(144f + dx, 98f + dy, 106f + dx, 108f + dy, 106f + dx, 108f + dy); close()
        }
        c.drawPath(shape(-6f, -4f), paint().apply { color = top })
        c.drawPath(shape(6f, 4f), paint().apply { color = bot })
        solid(c, shape(0f, 0f), WHITE, 0xFFD9DEE6.toInt(), 30f, 170f)
    }
}
