package ai.nanosearch.launcher.clocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What a card shows: two digits and an optional small AM/PM mark. */
data class Face(val text: String, val ampm: String? = null)

/**
 * The flip-clock card art, drawn straight onto a canvas (no bitmap per frame): a light grey rounded card split across the middle with two dark
 * squarish digits, and the flip itself, where the top half folds down and the next bottom half lands, in perspective and with shading.
 */
class FlipFaces(context: Context) {
    private val bold: Typeface = Typeface.createFromAsset(context.assets, "Play-Bold.ttf")
    private val regular: Typeface = Typeface.createFromAsset(context.assets, "Play-Regular.ttf")
    private val faces = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }
    private val shadows = HashMap<String, Bitmap>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun margin(cw: Int, ch: Int) = (0.05f * min(cw, ch)).toInt().coerceAtLeast(2)

    private fun face(cw: Int, ch: Int, f: Face): Bitmap = faces.getOrPut("$cw,$ch,${f.text},${f.ampm}") { drawFace(cw, ch, f) }

    private fun drawFace(cw: Int, ch: Int, f: Face): Bitmap {
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = 0.085f * min(cw, ch)
        val rect = RectF(0f, 0f, cw.toFloat(), ch.toFloat())
        val mid = ch / 2f
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, mid, 0xFFF7F7F7.toInt(), 0xFFDCDCDC.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, cw.toFloat(), mid, p)
        p.shader = LinearGradient(0f, mid, 0f, ch.toFloat(), 0xFFCFCFCF.toInt(), 0xFFEBEBEB.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, mid, cw.toFloat(), ch.toFloat(), p)
        p.shader = null

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }
        var size = 0.66f * ch
        text.textSize = size
        val w = text.measureText(f.text)
        val maxW = 0.82f * cw
        if (w > maxW) { size *= maxW / w; text.textSize = size }
        val b = Rect()
        text.getTextBounds("0", 0, 1, b)
        val baseline = mid + b.height() / 2f
        text.color = 0xCCFFFFFF.toInt()
        c.drawText(f.text, cw / 2f, baseline + 0.012f * ch, text)
        text.color = 0xFF2A2A2A.toInt()
        c.drawText(f.text, cw / 2f, baseline, text)
        if (f.ampm != null) {
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 0.105f * ch; color = 0xFF555555.toInt() }
            c.drawText(f.ampm, 0.075f * cw, ch - 0.075f * ch, small)
        }
        val line = Paint().apply { color = 0xCC4E4E4E.toInt() }
        val thick = (0.012f * ch).coerceAtLeast(1.5f)
        c.drawRect(0f, mid - thick / 2, cw.toFloat(), mid + thick / 2, line)
        line.color = 0x99FFFFFF.toInt()
        c.drawRect(0f, mid + thick / 2, cw.toFloat(), mid + thick, line)
        c.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = (0.008f * ch).coerceAtLeast(1f); color = 0x66000000 }
        c.drawRoundRect(RectF(rect).apply { inset(edge.strokeWidth / 2, edge.strokeWidth / 2) }, r, r, edge)
        return bmp
    }

    /** The card's blurred shadow, made once per size: a hardware canvas cannot draw shadows under shapes, so it is a bitmap. */
    private fun shadow(cw: Int, ch: Int): Bitmap = shadows.getOrPut("$cw,$ch") {
        val m = margin(cw, ch)
        val bmp = Bitmap.createBitmap(cw + 2 * m, ch + 2 * m, Bitmap.Config.ARGB_8888)
        val r = 0.085f * min(cw, ch)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9A9A.toInt(); setShadowLayer(0.045f * min(cw, ch), 0f, 0.02f * ch, 0x88000000.toInt()) }
        Canvas(bmp).drawRoundRect(RectF(m.toFloat(), m.toFloat(), (m + cw).toFloat(), (m + ch).toFloat()), r, r, p)
        bmp
    }

    /** A card at rest with its top-left corner at ([x], [y]). */
    fun drawRest(c: Canvas, x: Float, y: Float, cw: Int, ch: Int, f: Face, alpha: Int = 255) {
        val m = margin(cw, ch)
        val paint = Paint(bitmapPaint).apply { this.alpha = alpha }
        c.drawBitmap(shadow(cw, ch), x - m, y - m, paint)
        c.drawBitmap(face(cw, ch, f), x, y, paint)
    }

    private fun darken(alpha: Int) = Paint(bitmapPaint).apply { colorFilter = PorterDuffColorFilter(Color.argb(alpha.coerceIn(0, 255), 0, 0, 0), PorterDuff.Mode.SRC_ATOP) }

    /** [old] flipping to [new]; [p] runs 0..1. */
    fun drawFlip(c: Canvas, x: Float, y: Float, cw: Int, ch: Int, old: Face, new: Face, p: Float) {
        if (p <= 0f) return drawRest(c, x, y, cw, ch, old)
        if (p >= 1f) return drawRest(c, x, y, cw, ch, new)
        val m = margin(cw, ch)
        c.drawBitmap(shadow(cw, ch), x - m, y - m, bitmapPaint)
        val hy = ch / 2f
        val oldFace = face(cw, ch, old)
        val newFace = face(cw, ch, new)
        val early = p < 0.5f
        val u = if (early) p / 0.5f else 0f
        val v = if (early) 0f else (p - 0.5f) / 0.5f

        c.save(); c.clipRect(x, y, x + cw, y + hy)            // the new top, in the flap's shadow until it has gone
        c.drawBitmap(newFace, x, y, if (early) darken((90 * (1f - u)).toInt()) else bitmapPaint)
        c.restore()
        c.save(); c.clipRect(x, y + hy, x + cw, y + ch)       // the old bottom, shaded as the new bottom lands
        c.drawBitmap(oldFace, x, y, if (early) bitmapPaint else darken((90 * (1f - v)).toInt()))
        c.restore()

        if (early) flap(c, oldFace, true, 90f * u.pow(1.6f), x, y, cw, ch, (150 * u).toInt())
        else { val phi = 90f * (1f - v).pow(2f); flap(c, newFace, false, phi, x, y, cw, ch, (150 * (phi / 90f)).toInt()) }
    }

    private fun flap(c: Canvas, face: Bitmap, isTop: Boolean, angleDeg: Float, x: Float, y: Float, cw: Int, ch: Int, shadeAlpha: Int) {
        val a = angleDeg * PI.toFloat() / 180f
        val hy = ch / 2f
        val axis = y + hy
        val cx = x + cw / 2f
        val distance = ch * 2.6f
        val s = distance / (distance - hy * sin(a))
        val reach = hy * cos(a) * s
        val halfW = cw / 2f * s
        val dst = if (isTop) floatArrayOf(cx - halfW, axis - reach, cx + halfW, axis - reach, x + cw, axis, x, axis)
        else floatArrayOf(x, axis, x + cw, axis, cx + halfW, axis + reach, cx - halfW, axis + reach)
        // the half's corners, in the card face's own pixel coordinates; the face is drawn at the origin under the matrix
        val src = if (isTop) floatArrayOf(0f, 0f, cw.toFloat(), 0f, cw.toFloat(), hy, 0f, hy)
        else floatArrayOf(0f, hy, cw.toFloat(), hy, cw.toFloat(), ch.toFloat(), 0f, ch.toFloat())
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        c.save()
        c.concat(m)
        if (isTop) c.clipRect(0f, 0f, cw.toFloat(), hy) else c.clipRect(0f, hy, cw.toFloat(), ch.toFloat())
        c.drawBitmap(face, 0f, 0f, darken(shadeAlpha))
        c.restore()
    }
}
