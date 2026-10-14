package ai.nanosearch.launcher.ui

import android.content.Context
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

/**
 * The surfaces of the Spirit style: glossy glass with weight. A lit upper half over a darker lower half (the "gel" split), a bright edge along the
 * top, a dark border all round and a soft shadow underneath when the view has an elevation. [Look] picks the glass: dark for bars and buttons,
 * a flatter one for cards, and a coloured one for whatever is selected.
 */
class SpiritGlass(context: Context, radiusDp: Float, private val look: Look = Look.DARK, private val accent: Int = 0xFF1AA0E0.toInt(), private val opacity: Float = 1f, private val topOnly: Boolean = false) : Drawable() {
    enum class Look { DARK, DOCK, CARD, ACCENT }

    private val d = context.resources.displayMetrics.density
    private val r = radiusDp * d
    private val rect = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG)

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
        // the top half and the bottom half are separate gradients, so the lit half ends in a crisp edge
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), intArrayOf(topA, topB, botA, botB), floatArrayOf(0f, 0.499f, 0.5f, 1f), Shader.TileMode.CLAMP)
        line.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, intArrayOf(0x00FFFFFF, highlight, highlight, 0x00FFFFFF), floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP)
        lip.shader = LinearGradient(0f, b.bottom - 3 * d, 0f, b.bottom.toFloat(), 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val a = (255 * opacity).toInt()
        fill.alpha = a; line.alpha = a; lip.alpha = a
        c.save(); c.clipPath(clip)
        c.drawRect(rect, fill)
        c.drawRect(rect.left, rect.top + 1f * d, rect.right, rect.top + 2.2f * d, line)
        c.drawRect(rect.left, rect.bottom - 3 * d, rect.right, rect.bottom, lip)
        c.restore()
        stroke.strokeWidth = 1f * d; stroke.color = rim; stroke.alpha = ((rim ushr 24) * opacity).toInt()
        val i1 = 1.5f * d
        c.drawPath(Path().apply { addRoundRect(RectF(rect.left + i1, rect.top + i1, rect.right - i1, rect.bottom - i1), radii(i1), Path.Direction.CW) }, stroke)
        stroke.strokeWidth = 1.2f * d; stroke.color = border; stroke.alpha = ((border ushr 24) * opacity.coerceAtLeast(0.7f)).toInt()
        val i0 = stroke.strokeWidth / 2
        if (look == Look.DOCK && r == 0f) { // a bar that runs edge to edge: a border along its top and bottom only
            c.drawLine(rect.left, rect.top + i0, rect.right, rect.top + i0, stroke); c.drawLine(rect.left, rect.bottom - i0, rect.right, rect.bottom - i0, stroke)
        } else c.drawPath(Path().apply { addRoundRect(RectF(rect.left + i0, rect.top + i0, rect.right - i0, rect.bottom - i0), radii(i0), Path.Direction.CW) }, stroke)
    }

    /** Corner radii for a path inset by [inset]: all four corners, or only the top two for a bar that rises from the bottom of the screen. */
    private fun radii(inset: Float): FloatArray {
        val v = (r - inset).coerceAtLeast(0f)
        return if (topOnly) floatArrayOf(v, v, v, v, 0f, 0f, 0f, 0f) else floatArrayOf(v, v, v, v, v, v, v, v)
    }

    override fun getOutline(outline: Outline) { if (topOnly) outline.setConvexPath(clip) else outline.setRoundRect(bounds, r); outline.alpha = 0.9f }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun lighten(c: Int, f: Float) = mix(c, 0xFFFFFFFF.toInt(), f)
        fun darken(c: Int, f: Float) = mix(c, 0xFF000000.toInt(), f)
        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
