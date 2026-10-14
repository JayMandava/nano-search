package ai.nanosearch.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * The surfaces of the Spirit style: glossy glass with weight. A lit upper half over a darker lower half (the "gel" split), a bright edge along the
 * top, a dark border all round and a soft shadow underneath when the view has an elevation. [Look] picks the glass: dark for bars and buttons,
 * a flatter one for cards, and a coloured one for whatever is selected.
 *
 * The glass is painted once per size into a small bitmap and then drawn as a single picture: a screen full of tiles then costs the graphics chip
 * one textured square each, instead of a gradient, three outlines and two highlights each, on every frame of a scroll.
 */
class SpiritGlass(context: Context, radiusDp: Float, private val look: Look = Look.DARK, private val accent: Int = 0xFF1AA0E0.toInt(), private val opacity: Float = 1f, private val topOnly: Boolean = false) : Drawable() {
    enum class Look { DARK, DOCK, CARD, ACCENT }

    private val d = context.resources.displayMetrics.density
    private val r = radiusDp * d
    private val rect = RectF()
    private val clip = Path()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    private val topA: Int; private val topB: Int; private val botA: Int; private val botB: Int
    private val highlight: Int; private val rim: Int; private val border: Int

    init {
        when (look) {
            Look.DARK -> { topA = 0xFF4A4F55.toInt(); topB = 0xFF2E3237.toInt(); botA = 0xFF1B1D20.toInt(); botB = 0xFF0D0E10.toInt(); highlight = 0x77FFFFFF; rim = 0x26FFFFFF; border = 0xDD000000.toInt() }
            Look.DOCK -> { topA = 0xFF3A3E43.toInt(); topB = 0xFF25282C.toInt(); botA = 0xFF16181A.toInt(); botB = 0xFF0A0B0C.toInt(); highlight = 0x66FFFFFF; rim = 0x14FFFFFF; border = 0xFF000000.toInt() }
            Look.CARD -> { topA = 0xFF353A40.toInt(); topB = 0xFF2B2F34.toInt(); botA = 0xFF2B2F34.toInt(); botB = 0xFF1B1D20.toInt(); highlight = 0x55FFFFFF; rim = 0x16FFFFFF; border = 0xCC000000.toInt() } // no split: a card is one smooth slab
            Look.ACCENT -> { topA = lighten(accent, 0.42f); topB = lighten(accent, 0.08f); botA = darken(accent, 0.12f); botB = darken(accent, 0.40f); highlight = 0xAAFFFFFF.toInt(); rim = 0x33FFFFFF; border = darken(accent, 0.68f) }
        }
    }

    override fun onBoundsChange(b: Rect) {
        rect.set(b); clip.reset(); clip.addRoundRect(rect, radii(0f), Path.Direction.CW)
    }

    /** Corner radii for a shape inset by [inset]: all four corners, or only the top two for a bar that rises from the bottom of the screen. */
    private fun radii(inset: Float): FloatArray {
        val v = (r - inset).coerceAtLeast(0f)
        return if (topOnly) floatArrayOf(v, v, v, v, 0f, 0f, 0f, 0f) else floatArrayOf(v, v, v, v, v, v, v, v)
    }

    override fun draw(c: Canvas) {
        val w = rect.width().toInt(); val h = rect.height().toInt()
        if (w <= 0 || h <= 0) return
        if (w.toLong() * h > 900_000L) { paint(c, 0f, 0f, w.toFloat(), h.toFloat(), rect.left, rect.top); return } // very large: drawn directly, not worth keeping
        val key = "$look|$accent|$r|$opacity|$topOnly|$w|$h"
        val bmp = cache.get(key) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paint(Canvas(it), 0f, 0f, w.toFloat(), h.toFloat(), 0f, 0f); cache.put(key, it) }
        c.drawBitmap(bmp, rect.left, rect.top, blit)
    }

    /** Paints the glass into [c] over (l, t, rt, b); [ox], [oy] shift the drawing when it is drawn straight onto the screen rather than into a bitmap. */
    private fun paint(c: Canvas, l: Float, t: Float, rt: Float, b: Float, ox: Float, oy: Float) {
        c.save(); c.translate(ox, oy)
        val box = RectF(l, t, rt, b)
        val a = (255 * opacity).toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // the top half and the bottom half are separate gradients, so the lit half ends in a crisp edge
            shader = LinearGradient(0f, t, 0f, b, intArrayOf(topA, topB, botA, botB), floatArrayOf(0f, 0.499f, 0.5f, 1f), Shader.TileMode.CLAMP); alpha = a
        }
        val shape = Path().apply { addRoundRect(box, radii(0f), Path.Direction.CW) }
        c.drawPath(shape, fill)
        // the bright edge along the top, kept inside the rounded corners
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(l, 0f, rt, 0f, intArrayOf(0x00FFFFFF, highlight, highlight, 0x00FFFFFF), floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP); alpha = a
        }
        val inset = r * 0.7f
        c.drawRect(l + inset, t + 1f * d, rt - inset, t + 2.2f * d, line)
        // the foot's shadow: the same shape painted with a gradient that is clear except in its last 3 dp, so the shape itself is the clip
        val lip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, b - 3 * d, 0f, b, 0x00000000, 0x66000000, Shader.TileMode.CLAMP); alpha = a
        }
        c.drawPath(shape, lip)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        stroke.strokeWidth = 1f * d; stroke.color = rim; stroke.alpha = ((rim ushr 24) * opacity).toInt()
        val i1 = 1.5f * d
        c.drawPath(Path().apply { addRoundRect(RectF(l + i1, t + i1, rt - i1, b - i1), radii(i1), Path.Direction.CW) }, stroke)
        stroke.strokeWidth = 1.2f * d; stroke.color = border; stroke.alpha = ((border ushr 24) * opacity.coerceAtLeast(0.7f)).toInt()
        val i0 = stroke.strokeWidth / 2
        if (look == Look.DOCK && r == 0f) { // a bar that runs edge to edge: a border along its top and bottom only
            c.drawLine(l, t + i0, rt, t + i0, stroke); c.drawLine(l, b - i0, rt, b - i0, stroke)
        } else c.drawPath(Path().apply { addRoundRect(RectF(l + i0, t + i0, rt - i0, b - i0), radii(i0), Path.Direction.CW) }, stroke)
        c.restore()
    }

    override fun getOutline(outline: Outline) { if (topOnly) outline.setConvexPath(clip) else outline.setRoundRect(bounds, r); outline.alpha = 0.9f }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The glass already painted, by what makes it: a handful of sizes cover a whole screen of tiles. */
        private val cache = LruCache<String, Bitmap>(24)

        fun lighten(c: Int, f: Float) = mix(c, 0xFFFFFFFF.toInt(), f)
        fun darken(c: Int, f: Float) = mix(c, 0xFF000000.toInt(), f)
        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
