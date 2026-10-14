package ai.nanosearch.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * The icons of the Classic style: small glossy objects (a handset, an envelope, a camera, a calendar page) drawn here in code, each with a soft
 * shadow. An app gets one when its name or package says what it is; every other app keeps its own icon, set on a matching dark glossy tile.
 */
object AppIcons {
    internal const val SIZE = 192
    private val cache = LruCache<String, Drawable>(160)

    fun clear() = cache.evictAll()

    /** The icon to show for [cn]: the app's own in Modern, the Classic one otherwise. */
    fun get(context: Context, cn: ComponentName): Drawable {
        val pm = context.packageManager
        val own = runCatching { pm.getActivityIcon(cn) }.getOrNull() ?: pm.defaultActivityIcon
        val label = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrDefault("")
        val pack = IconPacks.active(context)
        // a known app gets its own drawing; otherwise its kind of app (a handset, a camera...); otherwise a game's controller; otherwise its own picture
        var brand = BrandIcons.find(context, cn.packageName)
        val kind = if (brand != null) null else kindOf(cn.packageName, label)
        if (brand == null && kind == null && runCatching { pm.getActivityInfo(cn, 0).applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)) brand = BrandIcons.GAME
        val day = if (kind == "calendar" || pack.isNotEmpty()) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 0
        val key = "g|$pack|${cn.flattenToShortString()}|${brand?.id}|$kind|$day"
        return cache.get(key) ?: BitmapDrawable(context.resources, IconPacks.themed(context, cn, own, day) ?: spiritIcon(own, kind, day, brand)).also { cache.put(key, it) }
    }

    private val KINDS = listOf(
        "calendar" to "calendar", "clock" to "clock|alarm", "calculator" to "calculator", "camera" to "camera", "gallery" to "gallery|photos",
        "phone" to "dialer|\\bphone\\b|telephone|incallui", "message" to "messag|\\bsms\\b|\\bmms\\b", "email" to "gmail|\\bmail\\b|email|outlook|protonmail|proton mail",
        "browser" to "browser|chrome|brave|firefox|opera|vivaldi|duckduckgo|\\bedge\\b", "music" to "music|podcast|spotify|\\bplayer\\b",
        "contacts" to "contacts|\\bpeople\\b", "files" to "\\bfiles\\b|documents|file manager|filemanager|downloads", "settings" to "settings",
        "maps" to "maps|navigation", "notes" to "\\bnotes?\\b|\\bkeep\\b|memo", "store" to "play store|vending|app store|market",
        "video" to "video|youtube|newpipe|netflix|\\bvlc\\b|\\btube\\b", "recorder" to "recorder|dictaphone|voice memo", "weather" to "weather",
        "wallet" to "wallet", "tasks" to "\\btasks?\\b|to-?do",
    ).map { it.first to Regex(it.second) }

    /** What sort of app this is, or null when none of the drawn icons fits. */
    fun kindOf(pkg: String, label: String): String? {
        val text = "${pkg.lowercase()} ${label.lowercase()}"
        return KINDS.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    // ---------------------------------------------------------------- painting kit

    internal fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)
    internal fun lin(y0: Float, y1: Float, a: Int, b: Int) = LinearGradient(0f, y0, 0f, y1, a, b, Shader.TileMode.CLAMP)
    internal fun lin3(y0: Float, y1: Float, a: Int, b: Int, c: Int) = LinearGradient(0f, y0, 0f, y1, intArrayOf(a, b, c), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)

    internal fun Canvas.shadowOf(path: Path, dy: Float = 6f, blur: Float = 7f, alpha: Int = 0x70) {
        val p = paint().apply { color = alpha shl 24; maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
        save(); translate(0f, dy); drawPath(path, p); restore()
    }

    internal fun Canvas.glossy(path: Path, top: Int, bottom: Int, y0: Float, y1: Float, shadow: Boolean = true, edge: Boolean = true) {
        if (shadow) shadowOf(path)
        drawPath(path, paint().apply { shader = lin(y0, y1, top, bottom) })
        if (edge) {
            drawPath(path, paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = 0x66FFFFFF })
        }
    }

    internal fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }

    /** A soft white sheen over the upper part of [path], the "glass" look. */
    internal fun Canvas.sheen(path: Path, y0: Float, y1: Float, alpha: Int = 0x66) {
        save(); clipPath(path)
        drawRect(0f, y0, SIZE.toFloat(), y1, paint().apply { shader = lin(y0, y1, (alpha shl 24) or 0xFFFFFF, 0x00FFFFFF) })
        restore()
    }

    internal fun bitmap(block: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { block(Canvas(it)) }

    // ---------------------------------------------------------------- Spirit: the glossy gem

    /**
     * The metal each gem is cut from. Restrained, as on the original: three neutrals (gunmetal blue for communication, graphite for media, steel for tools),
     * with the colour left to the drawn object on top.
     */
    private const val GUNMETAL = 0xFF3E4C5E; private const val GRAPHITE = 0xFF3D4147; private const val STEEL = 0xFF7C838C
    private val GEM_COLOURS: Map<String, Long> = mapOf(
        "phone" to GUNMETAL, "message" to GUNMETAL, "email" to GUNMETAL, "contacts" to GUNMETAL, "browser" to GUNMETAL, "ask" to 0xFF2F6A9C,
        "music" to GRAPHITE, "video" to GRAPHITE, "gallery" to GRAPHITE, "camera" to GRAPHITE, "recorder" to GRAPHITE, "maps" to GRAPHITE,
        "clock" to STEEL, "calendar" to STEEL, "calculator" to STEEL, "files" to STEEL, "settings" to STEEL, "notes" to STEEL, "tasks" to STEEL,
        "wallet" to STEEL, "weather" to STEEL, "store" to STEEL,
    )

    /** Any picture set in a gem: for an app that has no launcher entry of its own to ask. */
    fun gemOf(context: Context, own: Drawable): Drawable = BitmapDrawable(context.resources, spiritIcon(own, null, 0))

    /** The gem for a kind of thing that is not an app (a missed call, an event): the same look, cached. */
    fun kindGem(context: Context, kind: String, day: Int = 0): Drawable {
        val key = "k|$kind|$day"
        return cache.get(key) ?: BitmapDrawable(context.resources, gem((GEM_COLOURS[kind] ?: 0xFF3B4048).toInt(), draw(kind, day), 0.66f)).also { cache.put(key, it) }
    }

    /** An app's gem: a drawn object on a coloured one, or another app's own picture on a gem tinted from its colours. */
    private fun spiritIcon(own: Drawable, kind: String?, day: Int, brand: BrandIcons.Brand? = null): Bitmap =
        if (brand != null) gem(BrandIcons.tint(brand), BrandIcons.draw(brand), 0.66f)
        else if (kind != null) gem((GEM_COLOURS[kind] ?: 0xFF3B4048).toInt(), draw(kind, day), 0.66f)
        else {
            val emblem = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            own.setBounds(0, 0, 144, 144); own.draw(Canvas(emblem))
            gem(dominant(own), emblem, 0.6f, shadow = true)
        }

    /** The vivid colour an icon is mostly made of; a plain white, grey or black icon gets graphite. */
    private fun dominant(own: Drawable): Int {
        val n = 24
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        own.setBounds(0, 0, n, n); own.draw(Canvas(b))
        val hsv = FloatArray(3)
        var r = 0f; var g = 0f; var bl = 0f; var total = 0f; var hc = 0f; var hs = 0f
        for (y in 0 until n) for (x in 0 until n) {
            val px = b.getPixel(x, y)
            if (px ushr 24 < 200) continue
            android.graphics.Color.colorToHSV(px, hsv)
            val w = hsv[1] * hsv[2] // vivid pixels count; grey, white and black count for nothing
            if (w < 0.08f) continue
            r += android.graphics.Color.red(px) * w; g += android.graphics.Color.green(px) * w; bl += android.graphics.Color.blue(px) * w; total += w
            val h = Math.toRadians(hsv[0].toDouble()); hc += (w * cos(h)).toFloat(); hs += (w * sin(h)).toFloat()
        }
        if (total < 1.2f || Math.hypot(hc.toDouble(), hs.toDouble()) / total < 0.6) return 0xFF3B4048.toInt() // plain, or many colours (a mean of them would be mud): graphite
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (bl / total).toInt()), hsv)
        hsv[1] = (hsv[1] * 0.4f).coerceIn(0.16f, 0.36f); hsv[2] = hsv[2].coerceIn(0.36f, 0.5f) // a tint of the app's colour in graphite, never a loud tile
        return android.graphics.Color.HSVToColor(hsv)
    }

    /**
     * A glossy gem: a rounded tile that is lit from above (light at the top, deep at the bottom), a bevelled rim, light bouncing up from its foot, a curved
     * gloss over its upper half, and the [emblem] standing on it.
     */
    internal fun gem(base: Int, emblem: Bitmap, scale: Float, shadow: Boolean = false): Bitmap = bitmap { c ->
        val l = 14f; val t = 10f; val r = 178f; val b = 174f
        val tile = rr(l, t, r, b, 42f)
        val hi = ai.nanosearch.launcher.ui.SpiritGlass.lighten(base, 0.30f); val lo = ai.nanosearch.launcher.ui.SpiritGlass.darken(base, 0.5f)
        c.shadowOf(tile, dy = 8f, blur = 9f, alpha = 0x90)
        c.drawPath(tile, paint().apply { shader = LinearGradient(0f, t, 0f, b, intArrayOf(hi, base, lo), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
        c.save(); c.clipPath(tile)
        // light bouncing up from the foot
        c.drawCircle(96f, b + 6f, 96f, paint().apply { shader = RadialGradient(96f, b + 6f, 96f, 0x44FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) })
        c.restore()
        // the emblem
        val half = 192f * scale / 2f
        val dest = RectF(96f - half, 92f - half, 96f + half, 92f + half)
        if (shadow) {
            val sh = emblem.extractAlpha(paint().apply { maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL) }, intArrayOf(0, 0))
            c.drawBitmap(sh, null, RectF(dest).apply { offset(0f, 5f) }, paint().apply { color = 0xAA000000.toInt() })
        }
        c.drawBitmap(emblem, null, dest, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // the curved gloss over the upper half
        c.save(); c.clipPath(tile)
        val gloss = Path().apply { addOval(RectF(l - 44f, t - 150f, r + 44f, t + 82f), Path.Direction.CW) }
        c.drawPath(gloss, paint().apply { shader = lin(t, t + 82f, 0xB4FFFFFF.toInt(), 0x16FFFFFF) })
        c.drawPath(gloss, paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = 0x33FFFFFF })
        c.restore()
        // the bevelled rim, bright on the upper left, dark on the lower right, and a keyline round the edge
        c.drawPath(rr(l + 2f, t + 2f, r - 2f, b - 2f, 40f), paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 3.6f
            shader = LinearGradient(l, t, r, b, intArrayOf(0xCCFFFFFF.toInt(), 0x22FFFFFF, 0x00000000, 0x88000000.toInt()), floatArrayOf(0f, 0.35f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawPath(tile, paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = 0xCC000000.toInt() })
    }

    /** An app without a drawn icon: its own picture on a dark glossy tile, so the grid stays uniform. (The earlier Spirit look.) */
    private fun tile(own: Drawable): Bitmap = bitmap { c ->
        val t = rr(14f, 14f, 178f, 178f, 34f)
        c.glossy(t, 0xFF3a3d41.toInt(), 0xFF17191b.toInt(), 14f, 178f)
        c.sheen(t, 14f, 100f, 0x40)
        own.setBounds(40, 36, 152, 148)
        own.draw(c)
    }

    // ---------------------------------------------------------------- the icons

    fun draw(kind: String, day: Int = 0): Bitmap = bitmap { c ->
        when (kind) {
            "phone" -> phone(c); "message" -> message(c); "email" -> email(c); "browser" -> browser(c); "camera" -> camera(c)
            "gallery" -> gallery(c); "music" -> music(c); "clock" -> clock(c); "calendar" -> calendar(c, day); "calculator" -> calculator(c)
            "contacts" -> contacts(c); "files" -> files(c); "settings" -> settings(c); "maps" -> maps(c); "notes" -> notes(c)
            "store" -> store(c); "video" -> video(c); "recorder" -> recorder(c); "weather" -> weather(c); "wallet" -> wallet(c); "tasks" -> tasks(c)
            "ask" -> ask(c)
        }
    }

    val ALL = listOf("phone", "message", "email", "browser", "camera", "gallery", "music", "clock", "calendar", "calculator", "contacts", "files", "settings", "maps", "notes", "store", "video", "recorder", "weather", "wallet", "tasks")

    /** The "ask" gem's emblem: a large and a small four-pointed spark. */
    internal fun ask(c: Canvas) {
        fun spark(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0 - 90.0); val r = if (i % 2 == 0) outer else inner
                val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val big = spark(88f, 100f, 70f, 17f); val small = spark(150f, 50f, 30f, 8f)
        for (sp in listOf(big, small)) {
            c.shadowOf(sp, dy = 5f, blur = 6f, alpha = 0x70)
            c.drawPath(sp, paint().apply { shader = lin(30f, 170f, 0xFFFFFFFF.toInt(), 0xFFBFD9F2.toInt()) })
            c.drawPath(sp, paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = 0x66FFFFFF })
        }
    }

    private fun phone(c: Canvas) {
        val curve = Path().apply { moveTo(46f, 52f); cubicTo(40f, 132f, 100f, 160f, 146f, 146f) }
        val ends = Path().apply { addRoundRect(RectF(30f, 36f, 74f, 78f), 12f, 12f, Path.Direction.CW); addRoundRect(RectF(118f, 118f, 164f, 160f), 12f, 12f, Path.Direction.CW) }
        c.save(); c.translate(0f, 6f)
        c.drawPath(curve, paint().apply { style = Paint.Style.STROKE; strokeWidth = 30f; strokeCap = Paint.Cap.ROUND; color = 0x70000000; maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL) })
        c.restore()
        c.drawPath(curve, paint().apply { style = Paint.Style.STROKE; strokeWidth = 30f; strokeCap = Paint.Cap.ROUND; shader = lin(40f, 160f, 0xFFf2f3f5.toInt(), 0xFF8e949b.toInt()) })
        c.drawPath(curve, paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = 0x99FFFFFF.toInt() })
        c.drawPath(ends, paint().apply { shader = lin(36f, 160f, 0xFFdfe2e6.toInt(), 0xFF6f757c.toInt()) })
        c.drawPath(ends, paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x66FFFFFF })
    }

    private fun message(c: Canvas) {
        val bubble = Path().apply {
            addRoundRect(RectF(22f, 34f, 170f, 138f), 26f, 26f, Path.Direction.CW)
            moveTo(52f, 130f); lineTo(46f, 168f); lineTo(88f, 136f); close()
        }
        c.glossy(bubble, 0xFF59c3ff.toInt(), 0xFF0a6fbd.toInt(), 34f, 170f)
        c.sheen(bubble, 34f, 96f)
        val line = paint().apply { color = 0xE6FFFFFF.toInt(); strokeWidth = 11f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(52f, 70f, 140f, 70f, line); c.drawLine(52f, 96f, 118f, 96f, line)
    }

    private fun email(c: Canvas) {
        val body = rr(18f, 42f, 174f, 150f, 14f)
        c.glossy(body, 0xFFffffff.toInt(), 0xFFc9ced4.toInt(), 42f, 150f)
        val flap = Path().apply { moveTo(22f, 50f); lineTo(96f, 108f); lineTo(170f, 50f) }
        c.drawPath(flap, paint().apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; color = 0xFF9aa3ad.toInt() })
        c.drawPath(Path().apply { moveTo(24f, 142f); lineTo(72f, 90f); moveTo(168f, 142f); lineTo(120f, 90f) }, paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFb5bcc4.toInt() })
    }

    private fun browser(c: Canvas) {
        val cx = 96f; val cy = 98f; val r = 70f
        c.drawCircle(cx, cy + 6f, r, paint().apply { color = 0x70000000; maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(cx, cy, r, paint().apply { shader = RadialGradient(cx - 22f, cy - 28f, r * 1.3f, intArrayOf(0xFF8fd8ff.toInt(), 0xFF2a8ee0.toInt(), 0xFF0a4f9e.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
        val grid = paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x88FFFFFF.toInt() }
        c.drawOval(RectF(cx - 30f, cy - r, cx + 30f, cy + r), grid); c.drawOval(RectF(cx - 60f, cy - r + 6f, cx + 60f, cy + r - 6f), grid)
        c.drawLine(cx - r, cy, cx + r, cy, grid); c.drawLine(cx, cy - r, cx, cy + r, grid)
        c.drawArc(RectF(cx - r + 10f, cy - 22f, cx + r - 10f, cy + 22f), 0f, 180f, false, grid)
        c.drawArc(RectF(cx - r - 14f, cy - r - 14f, cx + r + 14f, cy + r + 14f), 200f, 100f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; color = 0xFFffa31a.toInt() })
        c.drawCircle(cx - 22f, cy - 30f, 20f, paint().apply { shader = RadialGradient(cx - 26f, cy - 36f, 24f, 0x80FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP) })
    }

    private fun camera(c: Canvas) {
        val body = rr(14f, 52f, 178f, 156f, 20f)
        c.glossy(body, 0xFF45474b.toInt(), 0xFF18191b.toInt(), 52f, 156f)
        val top = Path().apply { addRoundRect(RectF(14f, 52f, 178f, 78f), 16f, 16f, Path.Direction.CW) }
        c.save(); c.clipPath(body); c.drawPath(top, paint().apply { shader = lin(52f, 78f, 0xFFe4e6e9.toInt(), 0xFF9ca1a7.toInt()) }); c.restore()
        c.drawRoundRect(RectF(36f, 36f, 74f, 56f), 6f, 6f, paint().apply { shader = lin(36f, 56f, 0xFFd4d7db.toInt(), 0xFF868b92.toInt()) })
        c.drawCircle(96f, 106f, 48f, paint().apply { shader = lin(58f, 154f, 0xFFeceef0.toInt(), 0xFF7f858c.toInt()) })
        c.drawCircle(96f, 106f, 38f, paint().apply { color = 0xFF111214.toInt() })
        c.drawCircle(96f, 106f, 30f, paint().apply { shader = RadialGradient(88f, 96f, 40f, intArrayOf(0xFF4a6a8c.toInt(), 0xFF14223a.toInt(), 0xFF05080e.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP) })
        c.drawCircle(84f, 92f, 9f, paint().apply { color = 0xAAFFFFFF.toInt() })
        c.drawCircle(146f, 92f, 6f, paint().apply { color = 0xFFffb020.toInt() })
    }

    private fun gallery(c: Canvas) {
        c.save(); c.rotate(-8f, 96f, 96f)
        c.glossy(rr(40f, 30f, 168f, 126f, 8f), 0xFFf4f6f8.toInt(), 0xFFb8bec5.toInt(), 30f, 126f)
        c.restore()
        val front = rr(24f, 52f, 156f, 158f, 8f)
        c.glossy(front, 0xFFffffff.toInt(), 0xFFd3d8dd.toInt(), 52f, 158f)
        val pic = RectF(34f, 62f, 146f, 148f)
        c.save(); c.clipRect(pic)
        c.drawRect(pic, paint().apply { shader = lin(62f, 148f, 0xFF7cc7ff.toInt(), 0xFFd7efff.toInt()) })
        c.drawCircle(112f, 90f, 13f, paint().apply { color = 0xFFffd23c.toInt() })
        c.drawPath(Path().apply { moveTo(34f, 148f); lineTo(70f, 98f); lineTo(98f, 132f); lineTo(116f, 112f); lineTo(146f, 148f); close() }, paint().apply { shader = lin(98f, 148f, 0xFF59b85a.toInt(), 0xFF2c7c34.toInt()) })
        c.restore()
    }

    private fun music(c: Canvas) {
        fun note(x: Float, y: Float, top: Int, bottom: Int) {
            val head = Path().apply { addOval(RectF(x, y, x + 40f, y + 30f), Path.Direction.CW) }
            c.save(); c.rotate(-18f, x + 20f, y + 15f); c.glossy(head, top, bottom, y, y + 30f); c.restore()
        }
        val stems = Path().apply { addRect(RectF(68f, 40f, 78f, 132f), Path.Direction.CW); addRect(RectF(126f, 26f, 136f, 118f), Path.Direction.CW); moveTo(68f, 40f); lineTo(136f, 26f); lineTo(136f, 54f); lineTo(68f, 68f); close() }
        c.glossy(stems, 0xFF58c1ff.toInt(), 0xFF1478c8.toInt(), 26f, 132f)
        note(34f, 116f, 0xFF67c8ff.toInt(), 0xFF1a7fd0.toInt()); note(92f, 102f, 0xFFc9ced4.toInt(), 0xFF7f868e.toInt())
        c.sheen(stems, 26f, 80f, 0x55)
    }

    private fun clock(c: Canvas) {
        val bell = paint().apply { shader = lin(20f, 60f, 0xFFdfe2e6.toInt(), 0xFF868b92.toInt()) }
        c.drawCircle(46f, 44f, 22f, bell); c.drawCircle(146f, 44f, 22f, bell)
        c.drawCircle(96f, 106f, 74f, paint().apply { color = 0x70000000; maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(96f, 104f, 72f, paint().apply { shader = lin(32f, 176f, 0xFFf1f2f4.toInt(), 0xFF7d838a.toInt()) })
        c.drawCircle(96f, 104f, 60f, paint().apply { shader = lin(44f, 164f, 0xFFfdfdfd.toInt(), 0xFFdadde1.toInt()) })
        val tick = paint().apply { color = 0xFF3a3d42.toInt(); strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 12) { val a = Math.toRadians(i * 30.0); val r0 = if (i % 3 == 0) 46f else 51f
            c.drawLine(96f + (r0 * sin(a)).toFloat(), 104f - (r0 * cos(a)).toFloat(), 96f + (56f * sin(a)).toFloat(), 104f - (56f * cos(a)).toFloat(), tick) }
        val hand = paint().apply { color = 0xFF1d1f22.toInt(); strokeCap = Paint.Cap.ROUND }
        c.drawLine(96f, 104f, 96f, 62f, hand.apply { strokeWidth = 6f }); c.drawLine(96f, 104f, 124f, 118f, hand.apply { strokeWidth = 7f })
        c.drawLine(96f, 104f, 74f, 148f, paint().apply { color = 0xFFd8212b.toInt(); strokeWidth = 3f; strokeCap = Paint.Cap.ROUND })
        c.drawCircle(96f, 104f, 6f, paint().apply { color = 0xFF1d1f22.toInt() })
    }

    private fun calendar(c: Canvas, day: Int) {
        val page = rr(26f, 30f, 166f, 168f, 14f)
        c.glossy(page, 0xFFffffff.toInt(), 0xFFcfd4d9.toInt(), 30f, 168f)
        val head = Path().apply { addRoundRect(RectF(26f, 30f, 166f, 74f), 14f, 14f, Path.Direction.CW) }
        c.save(); c.clipPath(page); c.drawRect(26f, 30f, 166f, 72f, paint().apply { shader = lin(30f, 72f, 0xFF63c46a.toInt(), 0xFF2c8a33.toInt()) }); c.restore()
        c.drawRoundRect(RectF(54f, 18f, 66f, 46f), 5f, 5f, paint().apply { color = 0xFFf4f5f6.toInt() }); c.drawRoundRect(RectF(126f, 18f, 138f, 46f), 5f, 5f, paint().apply { color = 0xFFf4f5f6.toInt() })
        val t = paint().apply { color = 0xFF3b3f45.toInt(); textAlign = Paint.Align.CENTER; textSize = 78f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        c.drawText(if (day > 0) day.toString() else "31", 96f, 148f, t)
        c.drawPath(Path().apply { moveTo(26f, 74f); lineTo(166f, 74f) }, paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33000000 })
    }

    private fun calculator(c: Canvas) {
        val body = rr(32f, 18f, 160f, 176f, 16f)
        c.glossy(body, 0xFFb4b8be.toInt(), 0xFF6e747b.toInt(), 18f, 176f)
        c.drawRoundRect(RectF(44f, 32f, 148f, 66f), 6f, 6f, paint().apply { shader = lin(32f, 66f, 0xFF9fb6a2.toInt(), 0xFF6f8a74.toInt()) })
        c.drawText("1234", 142f, 60f, paint().apply { color = 0xFF1f2a22.toInt(); textAlign = Paint.Align.RIGHT; textSize = 26f; typeface = Typeface.MONOSPACE })
        for (row in 0 until 4) for (col in 0 until 4) {
            val x = 44f + col * 27f; val y = 80f + row * 22f
            val accent = col == 3
            c.drawRoundRect(RectF(x, y, x + 22f, y + 17f), 4f, 4f, paint().apply { shader = lin(y, y + 17f, if (accent) 0xFFffc83a.toInt() else 0xFFeceef0.toInt(), if (accent) 0xFFd98a10.toInt() else 0xFFaab0b6.toInt()) })
        }
    }

    private fun contacts(c: Canvas) {
        val book = rr(28f, 18f, 160f, 176f, 14f)
        c.glossy(book, 0xFF3a3b3f.toInt(), 0xFF141517.toInt(), 18f, 176f)
        c.drawPath(rr(36f, 26f, 152f, 168f, 9f), paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66FFFFFF; pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        for (i in 0 until 4) c.drawRoundRect(RectF(150f, 38f + i * 32f, 172f, 54f + i * 32f), 4f, 4f, paint().apply { shader = lin(38f + i * 32f, 54f + i * 32f, 0xFFe4e6e9.toInt(), 0xFF8d939a.toInt()) })
        val person = paint().apply { shader = lin(50f, 150f, 0xFF59c3ff.toInt(), 0xFF0f7cc8.toInt()) }
        c.drawCircle(94f, 80f, 20f, person)
        c.drawPath(Path().apply { moveTo(56f, 140f); cubicTo(56f, 108f, 132f, 108f, 132f, 140f); close() }, person)
    }

    private fun files(c: Canvas) {
        val cab = rr(32f, 20f, 160f, 174f, 12f)
        c.glossy(cab, 0xFFe8eaed.toInt(), 0xFF9aa0a7.toInt(), 20f, 174f)
        for (i in 0 until 2) {
            val y = 34f + i * 66f
            c.drawRoundRect(RectF(44f, y, 148f, y + 56f), 6f, 6f, paint().apply { shader = lin(y, y + 56f, 0xFFf7f8f9.toInt(), 0xFFbfc4ca.toInt()) })
            c.drawRoundRect(RectF(74f, y + 14f, 118f, y + 26f), 4f, 4f, paint().apply { color = 0xFF6f757c.toInt() })
            c.drawRoundRect(RectF(82f, y + 34f, 110f, y + 44f), 5f, 5f, paint().apply { shader = lin(y + 34f, y + 44f, 0xFFdfe2e5.toInt(), 0xFF868b92.toInt()) })
        }
    }

    private fun settings(c: Canvas) {
        val tile = rr(14f, 32f, 178f, 160f, 24f)
        c.glossy(tile, 0xFF45474b.toInt(), 0xFF141517.toInt(), 32f, 160f)
        c.sheen(tile, 32f, 100f, 0x30)
        val gear = Path()
        val teeth = 8
        for (i in 0 until teeth * 2) {
            val a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / 16; val a1 = a0 + Math.PI / teeth
            val r = if (i % 2 == 0) 50.0 else 38.0
            val x0 = 96 + r * cos(a0); val y0 = 96 + r * sin(a0); val x1 = 96 + r * cos(a1); val y1 = 96 + r * sin(a1)
            if (i == 0) gear.moveTo(x0.toFloat(), y0.toFloat()) else gear.lineTo(x0.toFloat(), y0.toFloat())
            gear.lineTo(x1.toFloat(), y1.toFloat())
        }
        gear.close(); gear.addCircle(96f, 96f, 15f, Path.Direction.CCW)
        c.drawPath(gear, paint().apply { shader = lin(46f, 146f, 0xFFf0f2f4.toInt(), 0xFF868c93.toInt()) })
    }

    private fun maps(c: Canvas) {
        val map = Path().apply { moveTo(20f, 48f); lineTo(70f, 36f); lineTo(122f, 52f); lineTo(172f, 38f); lineTo(172f, 150f); lineTo(122f, 164f); lineTo(70f, 148f); lineTo(20f, 162f); close() }
        c.shadowOf(map)
        c.save(); c.clipPath(map)
        c.drawRect(20f, 30f, 70f, 170f, paint().apply { color = 0xFF7fc47a.toInt() }); c.drawRect(70f, 30f, 122f, 170f, paint().apply { color = 0xFF5fae5a.toInt() }); c.drawRect(122f, 30f, 172f, 170f, paint().apply { color = 0xFF86cf80.toInt() })
        c.drawRect(20f, 30f, 172f, 100f, paint().apply { shader = lin(30f, 100f, 0x30FFFFFF, 0x00FFFFFF) })
        c.restore()
        c.drawPath(Path().apply { moveTo(34f, 140f); cubicTo(70f, 100f, 96f, 150f, 140f, 78f) }, paint().apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; color = 0xFF7a3fc4.toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f) })
        c.drawCircle(140f, 70f, 17f, paint().apply { shader = lin(53f, 87f, 0xFFb07cf0.toInt(), 0xFF6a2fb8.toInt()) }); c.drawCircle(140f, 70f, 6f, paint().apply { color = 0xFFffffff.toInt() })
    }

    private fun notes(c: Canvas) {
        val paper = Path().apply { moveTo(34f, 26f); lineTo(142f, 26f); lineTo(142f, 166f); lineTo(34f, 166f); close() }
        c.glossy(paper, 0xFFfff1a1.toInt(), 0xFFf2d54f.toInt(), 26f, 166f)
        val ln = paint().apply { color = 0x66527a9c; strokeWidth = 2.5f }
        for (i in 0 until 7) c.drawLine(40f, 56f + i * 17f, 136f, 56f + i * 17f, ln)
        c.drawLine(56f, 26f, 56f, 166f, paint().apply { color = 0x88e0524a.toInt(); strokeWidth = 2.5f })
        c.save(); c.rotate(38f, 140f, 60f)
        c.drawRoundRect(RectF(128f, 14f, 150f, 150f), 4f, 4f, paint().apply { shader = lin(14f, 150f, 0xFFffd23c.toInt(), 0xFFe29a10.toInt()) })
        c.drawPath(Path().apply { moveTo(128f, 150f); lineTo(139f, 176f); lineTo(150f, 150f); close() }, paint().apply { color = 0xFFf3d8b0.toInt() })
        c.drawPath(Path().apply { moveTo(135f, 168f); lineTo(139f, 178f); lineTo(143f, 168f); close() }, paint().apply { color = 0xFF3a3d42.toInt() })
        c.restore()
    }

    private fun store(c: Canvas) {
        val bag = Path().apply { moveTo(34f, 66f); lineTo(158f, 66f); lineTo(168f, 164f); lineTo(24f, 164f); close() }
        c.glossy(bag, 0xFF5cd6a3.toInt(), 0xFF0f8f69.toInt(), 66f, 164f)
        c.drawArc(RectF(62f, 22f, 130f, 106f), 180f, 180f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; color = 0xFFe9f6f0.toInt() })
        c.drawPath(Path().apply { moveTo(78f, 96f); lineTo(78f, 142f); lineTo(118f, 119f); close() }, paint().apply { color = 0xFFffffff.toInt() })
        c.sheen(bag, 66f, 110f, 0x40)
    }

    private fun video(c: Canvas) {
        val tile = rr(14f, 34f, 178f, 158f, 22f)
        c.glossy(tile, 0xFF3c3e42.toInt(), 0xFF0f1012.toInt(), 34f, 158f)
        c.drawPath(rr(20f, 40f, 172f, 152f, 18f), paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x55FFFFFF })
        val tri = Path().apply { moveTo(76f, 66f); lineTo(76f, 126f); lineTo(130f, 96f); close() }
        c.drawPath(tri, paint().apply { shader = lin(66f, 126f, 0xFF59c3ff.toInt(), 0xFF0a6fbd.toInt()) })
        c.sheen(tile, 34f, 90f, 0x28)
    }

    private fun recorder(c: Canvas) {
        c.drawArc(RectF(14f, 58f, 64f, 138f), 110f, 140f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = 0xFFe040d8.toInt() })
        c.drawArc(RectF(2f, 40f, 82f, 156f), 115f, 130f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = 0x99e040d8.toInt() })
        val cap = rr(78f, 22f, 134f, 108f, 28f)
        c.glossy(cap, 0xFFf3f4f6.toInt(), 0xFF868c93.toInt(), 22f, 108f)
        for (i in 0 until 5) c.drawLine(86f, 40f + i * 12f, 126f, 40f + i * 12f, paint().apply { color = 0x553a3d42; strokeWidth = 3f })
        c.drawArc(RectF(60f, 62f, 152f, 138f), 0f, 180f, false, paint().apply { style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; color = 0xFFb7bcc2.toInt() })
        c.drawRect(100f, 138f, 112f, 160f, paint().apply { color = 0xFF9aa0a7.toInt() }); c.drawRoundRect(RectF(78f, 156f, 134f, 170f), 6f, 6f, paint().apply { shader = lin(156f, 170f, 0xFFdfe2e5.toInt(), 0xFF7d838a.toInt()) })
    }

    private fun weather(c: Canvas) {
        c.drawCircle(124f, 66f, 40f, paint().apply { shader = RadialGradient(118f, 58f, 48f, intArrayOf(0xFFffe680.toInt(), 0xFFffb61e.toInt(), 0xFFf08a00.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP) })
        val cloud = Path()
        for (part in listOf(Path().apply { addCircle(66f, 124f, 32f, Path.Direction.CW) }, Path().apply { addCircle(104f, 108f, 40f, Path.Direction.CW) }, Path().apply { addCircle(142f, 126f, 28f, Path.Direction.CW) }, rr(40f, 116f, 168f, 156f, 20f)))
            cloud.op(part, Path.Op.UNION)
        c.glossy(cloud, 0xFFffffff.toInt(), 0xFFb9c4d0.toInt(), 68f, 156f)
    }

    private fun wallet(c: Canvas) {
        c.save(); c.rotate(-6f, 96f, 80f); c.drawRoundRect(RectF(44f, 28f, 150f, 100f), 6f, 6f, paint().apply { shader = lin(28f, 100f, 0xFF9ad39a.toInt(), 0xFF4f9d58.toInt()) }); c.restore()
        val body = rr(18f, 58f, 176f, 164f, 18f)
        c.glossy(body, 0xFFc98a4a.toInt(), 0xFF7a4820.toInt(), 58f, 164f)
        c.drawPath(rr(26f, 66f, 168f, 156f, 12f), paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66FFE8C8; pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        c.drawRoundRect(RectF(116f, 92f, 184f, 134f), 14f, 14f, paint().apply { shader = lin(92f, 134f, 0xFFd9985a.toInt(), 0xFF8a5226.toInt()) })
        c.drawCircle(142f, 113f, 9f, paint().apply { shader = lin(104f, 122f, 0xFFf1f3f5.toInt(), 0xFF868c93.toInt()) })
    }

    private fun tasks(c: Canvas) {
        val board = rr(30f, 22f, 162f, 176f, 12f)
        c.glossy(board, 0xFFb9814a.toInt(), 0xFF76481f.toInt(), 22f, 176f)
        c.drawRoundRect(RectF(42f, 40f, 150f, 166f), 5f, 5f, paint().apply { shader = lin(40f, 166f, 0xFFffffff.toInt(), 0xFFe1e5e8.toInt()) })
        c.drawRoundRect(RectF(66f, 14f, 126f, 40f), 8f, 8f, paint().apply { shader = lin(14f, 40f, 0xFFdfe2e5.toInt(), 0xFF7d838a.toInt()) })
        for (i in 0 until 3) {
            val y = 58f + i * 36f
            c.drawRoundRect(RectF(54f, y, 74f, y + 20f), 4f, 4f, paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF9aa3ad.toInt() })
            if (i < 2) c.drawPath(Path().apply { moveTo(57f, y + 11f); lineTo(64f, y + 18f); lineTo(80f, y - 2f) }, paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF2f9e3f.toInt() })
            c.drawLine(88f, y + 10f, 140f, y + 10f, paint().apply { color = 0xFFb3bac2.toInt(); strokeWidth = 4f; strokeCap = Paint.Cap.ROUND })
        }
    }
}
