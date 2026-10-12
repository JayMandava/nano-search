package ai.nanosearch.launcher.clocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * A steel-cased analogue dial, drawn in code. [drawFace] paints everything that does not move (case, minute track, dial, date window, numerals);
 * [drawHands] paints the three hands as vector shapes, so they stay sharp at any size and can be redrawn on every frame.
 */
class DialArt(context: Context) {
    // The numerals use Barlow (SIL OFL). A local build can drop a Porscha911.ttf into its own assets and that is used instead; it is never part of a release.
    private val face: Typeface = runCatching { Typeface.createFromAsset(context.assets, "Porscha911.ttf") }.getOrNull()
        ?: Typeface.createFromAsset(context.assets, "Barlow-SemiBold.ttf")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val tmp = Rect()

    /** Text centred on (x, y) by the ink of its glyphs, not by the font's line box. */
    private fun inkText(c: Canvas, s: String, x: Float, y: Float, paint: Paint) {
        paint.getTextBounds(s, 0, s.length, tmp)
        c.drawText(s, x - tmp.exactCenterX(), y - tmp.exactCenterY(), paint)
    }

    // a shader is modulated by the paint's alpha, so reset it to opaque before every gradient fill
    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, pos: FloatArray?, mode: Shader.TileMode): Shader { p.alpha = 255; return LinearGradient(x0, y0, x1, y1, colors, pos, mode) }
    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, mode: Shader.TileMode): Shader { p.alpha = 255; return LinearGradient(x0, y0, x1, y1, c0, c1, mode) }
    private fun radial(x: Float, y: Float, r: Float, colors: IntArray, pos: FloatArray?, mode: Shader.TileMode): Shader { p.alpha = 255; return RadialGradient(x, y, r, colors, pos, mode) }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())
    private fun px(cx: Float, r: Float, deg: Float) = cx + r * sin(rad(deg)).toFloat()
    private fun py(cy: Float, r: Float, deg: Float) = cy - r * cos(rad(deg)).toFloat()
    private fun caseRadius(size: Int) = size / 2f * 0.95f

    /** Sets [txt] so a digit's ink is [h] tall and [w] wide, whatever the typeface's own proportions. */
    private fun fitDigits(h: Float, w: Float) {
        txt.typeface = face; txt.textScaleX = 1f; txt.textSize = 100f; txt.letterSpacing = 0f
        txt.getTextBounds("0", 0, 1, tmp)
        val size = h / tmp.height() * 100f
        txt.textSize = size
        txt.getTextBounds("0", 0, 1, tmp)
        txt.textScaleX = w / tmp.width()
    }

    /** A "1" as on the dial: a stem with a flag at the top left and no foot. Local frame: top left at the origin, [h] tall. */
    private fun one(h: Float) = Path().apply {
        val w = h * 0.583f; val stem = h * 0.3f; val flag = h * 0.22f; val rc = h * 0.1f
        moveTo(0f, 0f); lineTo(w - rc, 0f); quadTo(w, 0f, w, rc); lineTo(w, h); lineTo(w - stem, h); lineTo(w - stem, flag); lineTo(0f, flag); close()
    }

    /** A "7" as on the dial: a bar and a plain diagonal leg. */
    private fun seven(h: Float, w: Float) = Path().apply {
        val t = h * 0.24f
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, t * 0.9f); lineTo(w * 0.34f + t * 1.15f, h); lineTo(w * 0.34f, h); lineTo(w - t * 1.45f, t); lineTo(0f, t); close()
    }

    /**
     * Draws [s] with its ink box centred on (cx, cy): digits [h] tall and [w] wide (a "1" is narrower), in the dial's heavy weight.
     * The "1" and "7" are drawn as shapes because the dial's own forms differ from the typeface's; everything else comes from the typeface.
     */
    private fun number(c: Canvas, s: String, cx: Float, cy: Float, h: Float, w: Float, color: Int, bold: Float, shadowR: Float, shadowDy: Float, shadowColor: Int) {
        val oneW = h * 0.583f; val gap = h * 0.25f
        val widths = s.map { if (it == '1') oneW else w }
        val total = widths.sum() + gap * (s.length - 1)
        var x = cx - total / 2f; val top = cy - h / 2f
        fitDigits(h - bold, w - bold)
        txt.color = color; txt.style = Paint.Style.FILL_AND_STROKE; txt.strokeWidth = bold; txt.strokeJoin = Paint.Join.MITER
        if (shadowR > 0f) txt.setShadowLayer(shadowR, 0f, shadowDy, shadowColor) else txt.clearShadowLayer()
        for ((i, ch) in s.withIndex()) {
            if (ch == '1' || ch == '7') {
                val path = if (ch == '1') one(h - bold) else seven(h - bold, w - bold)
                c.save(); c.translate(x + bold / 2f, top + bold / 2f)
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = bold; p.strokeJoin = Paint.Join.MITER; p.color = color
                if (shadowR > 0f) p.setShadowLayer(shadowR, 0f, shadowDy, shadowColor)
                c.drawPath(path, p); p.clearShadowLayer(); c.restore()
            } else {
                inkText(c, ch.toString(), x + widths[i] / 2f, cy, txt)
            }
            x += widths[i] + gap
        }
        txt.clearShadowLayer(); txt.style = Paint.Style.FILL; txt.strokeWidth = 0f
    }

    /** Everything on the dial that does not move. All sizes are fractions of the case radius, measured from the dial being matched. */
    fun drawFace(c: Canvas, size: Int) {
        val cx = size / 2f; val cy = size / 2f
        val r = caseRadius(size)
        val trackOut = 0.93f; val trackIn = 0.825f

        // the case's shadow on the wallpaper
        p.reset(); p.isAntiAlias = true
        p.color = 0x66000000; p.maskFilter = BlurMaskFilter(r * 0.05f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cy + r * 0.035f, r * 0.985f, p); p.maskFilter = null

        // the case: a steel band, pale at the top and shaded toward the bottom, inside a hairline edge
        p.shader = gradient(cx, cy - r, cx, cy + r, intArrayOf(0xFFbdbdbf.toInt(), 0xFF8c8c8e.toInt(), 0xFF595a5c.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null; p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.006f; p.color = 0xCC1b1b1d.toInt(); c.drawCircle(cx, cy, r * 0.997f, p)
        p.shader = gradient(cx - r, cy - r, cx + r * 0.3f, cy + r * 0.3f, 0xB3FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        p.strokeWidth = r * 0.008f; c.drawCircle(cx, cy, r * 0.982f, p)
        p.shader = null; p.style = Paint.Style.FILL

        // the minute track: dark grey at the top, pale at the bottom
        p.shader = gradient(cx, cy - r * trackOut, cx, cy + r * trackOut, intArrayOf(0xFF3b3b3d.toInt(), 0xFF919193.toInt(), 0xFFececed.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * trackOut, p)
        p.shader = null; p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.006f; p.color = 0xE6101112.toInt(); c.drawCircle(cx, cy, r * trackOut, p)
        p.style = Paint.Style.FILL

        // ticks hang from the outer edge of the track; every five minutes a small block sits against the dial, joined to its tick
        p.color = 0xFF111213.toInt(); p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 60) {
            val five = i % 5 == 0
            val a = i * 6f
            val inner = if (five) 0.87f else 0.886f
            p.strokeWidth = r * 0.0085f
            c.drawLine(px(cx, r * trackOut, a), py(cy, r * trackOut, a), px(cx, r * inner, a), py(cy, r * inner, a), p)
            if (five) {
                val w = r * (if (i % 15 == 0) 0.044f else 0.038f)
                c.save(); c.rotate(a, cx, cy)
                p.style = Paint.Style.FILL; c.drawRect(cx - w / 2, cy - r * 0.872f, cx + w / 2, cy - r * trackIn, p)
                c.restore(); p.style = Paint.Style.STROKE
            }
        }
        p.style = Paint.Style.FILL

        // the dial: grey, a little paler at the top
        p.shader = gradient(cx, cy - r * trackIn, cx, cy + r * trackIn, intArrayOf(0xFF8c8c8e.toInt(), 0xFF777779.toInt(), 0xFF646466.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * trackIn, p)
        p.shader = radial(cx, cy, r * trackIn, intArrayOf(0x00000000, 0x00000000, 0x33000000), floatArrayOf(0f, 0.9f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * trackIn, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.007f; p.color = 0xE6101112.toInt(); c.drawCircle(cx, cy, r * trackIn, p); p.style = Paint.Style.FILL

        // numerals. 12, 3, 6 and 9 are large, black and upright. The others are small and white, turned so they follow the dial and stay readable:
        // tilted with the hour on the top half, tipped back by half a turn on the bottom half (so 4 and 5 are never upside down).
        for (h in 1..12) {
            val big = h % 3 == 0
            val rad = r * (if (big) 0.70f else 0.72f)
            val x = px(cx, rad, h * 30f); val y = py(cy, rad, h * 30f)
            if (big) {
                number(c, h.toString(), x, y, r * 0.15f, r * 0.1875f, 0xFF121315.toInt(), r * 0.004f, r * 0.005f, r * 0.004f, 0x33FFFFFF)
            } else {
                val turn = when { h <= 2 -> h * 30f; h in 4..8 -> h * 30f - 180f; else -> h * 30f - 360f }
                c.save(); c.rotate(turn, x, y)
                number(c, h.toString(), x, y, r * 0.084f, r * 0.105f, 0xFFf7f7f8.toInt(), r * 0.003f, r * 0.01f, r * 0.006f, 0x80000000.toInt())
                c.restore()
            }
        }
        txt.clearShadowLayer()
    }

    // ---- hands: vector shapes in a frame where the pivot is the origin and the hand points straight up

    private fun handBody(len: Float, tail: Float, half: Float) = Path().apply {
        val tipHalf = half * 0.82f; val ch = half * 0.5f // slightly tapered, with the tip corners cut
        moveTo(-half, tail); lineTo(-half, -len + ch)
        lineTo(-tipHalf + ch * 0.4f, -len); lineTo(tipHalf - ch * 0.4f, -len)
        lineTo(tipHalf, -len + ch); lineTo(half, tail)
        close()
    }

    private fun drawHand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, tail: Float, half: Float, line: Float, lineFrom: Float, r: Float) {
        val body = handBody(len, tail, half)
        // soft shadow: a few faint copies stepping away toward the lower right
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = 0x10000000
        for (k in 1..5) { c.save(); c.translate(cx + r * 0.003f * k, cy + r * 0.0055f * k); c.rotate(deg); c.drawPath(body, p); c.restore() }
        c.save(); c.translate(cx, cy); c.rotate(deg)
        // black body, lit grey along its left edge
        p.shader = gradient(-half, 0f, half, 0f, intArrayOf(0xFF63656a.toInt(), 0xFF2a2b2e.toInt(), 0xFF0d0e10.toInt()), floatArrayOf(0f, 0.38f, 0.62f), Shader.TileMode.CLAMP)
        c.drawPath(body, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.003f; p.color = 0xFF050506.toInt(); c.drawPath(body, p) // a crisp dark outline
        // the thin white line down the outer part of the hand
        p.style = Paint.Style.FILL; p.color = 0xFFf8f8f9.toInt()
        c.drawRect(-line / 2f, -len + half * 0.9f, line / 2f, -lineFrom, p)
        c.restore()
    }

    /** The three hands and the pivot. [second] is fractional, so the seconds hand sweeps. */
    fun drawHands(c: Canvas, size: Int, hour: Int, minute: Int, second: Float) {
        val cx = size / 2f; val cy = size / 2f
        val r = caseRadius(size)
        val mDeg = (minute + second / 60f) * 6f
        val hDeg = ((hour % 12) + (minute + second / 60f) / 60f) * 30f
        val sDeg = second * 6f

        drawHand(c, cx, cy, hDeg, r * 0.56f, r * 0.08f, r * 0.037f, r * 0.02f, r * 0.26f, r)
        drawHand(c, cx, cy, mDeg, r * 0.78f, r * 0.09f, r * 0.031f, r * 0.019f, r * 0.22f, r)

        // seconds: a thin white hand with a red tip and a short tail
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = r * 0.019f; p.color = 0x18000000
        for (k in 1..4) { c.save(); c.translate(cx + r * 0.003f * k, cy + r * 0.0055f * k); c.rotate(sDeg); c.drawLine(0f, r * 0.24f, 0f, -r * 0.825f, p); c.restore() }
        c.save(); c.translate(cx, cy); c.rotate(sDeg)
        p.strokeWidth = r * 0.019f; p.color = 0xFFf6f6f7.toInt(); c.drawLine(0f, r * 0.24f, 0f, -r * 0.645f, p)
        p.strokeWidth = r * 0.02f; p.color = 0xFFd8212b.toInt(); c.drawLine(0f, -r * 0.645f, 0f, -r * 0.825f, p)
        c.restore()

        // the pivot: a white disc in a dark ring, with a dark dot
        p.style = Paint.Style.FILL
        p.color = 0xFF111214.toInt(); c.drawCircle(cx, cy, r * 0.06f, p)
        p.color = 0xFFf4f4f5.toInt(); c.drawCircle(cx, cy, r * 0.05f, p)
        p.color = 0xFF2b2c2f.toInt(); c.drawCircle(cx, cy, r * 0.011f, p)
    }

    /** A still picture of the whole clock, for the widget picker. */
    fun picture(size: Int, hour: Int, minute: Int, second: Float): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { val c = Canvas(it); drawFace(c, size); drawHands(c, size, hour, minute, second) }
}
