package ai.nanosearch.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wallpapers of the Sense era, drawn in code at the screen's size: a dusk over layered ridges, a bright meadow, ripples on dark water,
 * soft bokeh and a night skyline. Scenes in the spirit of that period's phones, all original artwork.
 */
object SenseWallpapers {
    val ALL = listOf(
        ClassicWallpapers.Paper("dusk", "Dusk"), ClassicWallpapers.Paper("meadow", "Meadow"), ClassicWallpapers.Paper("ripple", "Ripple"),
        ClassicWallpapers.Paper("bokeh", "Bokeh"), ClassicWallpapers.Paper("skyline", "Skyline"),
    )

    fun has(id: String) = ALL.any { it.id == id }

    fun render(id: String, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        when (id) {
            "dusk" -> dusk(c, w, h)
            "meadow" -> meadow(c, w, h)
            "ripple" -> ripple(c, w, h)
            "bokeh" -> bokeh(c, w, h)
            "skyline" -> skyline(c, w, h)
            else -> c.drawColor(0xFF111213.toInt())
        }
    }

    private fun vgrad(h: Int, vararg stops: Pair<Float, Int>) =
        LinearGradient(0f, 0f, 0f, h.toFloat(), stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)

    private fun fill(c: Canvas, w: Int, h: Int, shader: Shader) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
    }

    /** A rolling ridge line across the screen: [base] is its mean height as a fraction of [h], [amp] the swing, wavelengths in screen widths. */
    private fun ridge(w: Int, h: Int, base: Float, amp: Float, phase: Double, w1: Double, w2: Double): Path {
        val p = Path()
        p.moveTo(0f, h.toFloat())
        var x = 0
        while (x <= w + 8) {
            val t = x.toDouble() / w
            val y = h * base - h * amp * (0.6 * sin(2 * PI * (t * w1 + phase)) + 0.4 * sin(2 * PI * (t * w2 + phase * 1.7)))
            p.lineTo(x.toFloat(), y.toFloat())
            x += 8
        }
        p.lineTo(w.toFloat(), h.toFloat()); p.close()
        return p
    }

    private fun dusk(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF1b1744.toInt(), 0.35f to 0xFF6a2f7a.toInt(), 0.6f to 0xFFe0634a.toInt(), 0.78f to 0xFFffb35a.toInt(), 1f to 0xFFffd98a.toInt()))
        val sunX = w * 0.68f; val sunY = h * 0.64f
        val glow = Paint().apply { shader = RadialGradient(sunX, sunY, h * 0.36f, 0xCCFFE2A0.toInt(), 0x00FFB35A, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        c.drawCircle(sunX, sunY, w * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF3CF.toInt() })
        val layers = listOf(
            Triple(0.70f, 0xFF7a3b6a.toInt(), 0.05f), Triple(0.76f, 0xFF4d2858.toInt(), 0.06f),
            Triple(0.83f, 0xFF2c1a45.toInt(), 0.06f), Triple(0.91f, 0xFF150f2b.toInt(), 0.05f),
        )
        layers.forEachIndexed { i, (base, color, amp) ->
            c.drawPath(ridge(w, h, base, amp, 0.17 * i + 0.3, 1.3 + 0.4 * i, 3.1 + 0.5 * i), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
    }

    private fun meadow(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF2f7fd6.toInt(), 0.5f to 0xFF7fc4f2.toInt(), 0.72f to 0xFFd9f0fb.toInt(), 1f to 0xFFd9f0fb.toInt()))
        val rnd = Random(7)
        val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); maskFilter = BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL) }
        for (i in 0 until 6) {
            val cx = w * (0.1f + 0.8f * rnd.nextFloat()); val cy = h * (0.08f + 0.34f * rnd.nextFloat()); val s = w * (0.1f + 0.1f * rnd.nextFloat())
            for (j in 0 until 4) c.drawCircle(cx + (j - 1.5f) * s * 0.7f, cy + (if (j % 2 == 0) 0f else -s * 0.25f), s * (0.7f + 0.25f * rnd.nextFloat()), cloud)
        }
        val greens = listOf(0xFF8fcf4f.toInt() to 0xFF5aa32e.toInt(), 0xFF6fb83e.toInt() to 0xFF3f8a24.toInt(), 0xFF3f9a2e.toInt() to 0xFF1f6a1c.toInt())
        greens.forEachIndexed { i, (top, bottom) ->
            val base = 0.64f + 0.1f * i
            val path = ridge(w, h, base, 0.045f, 0.2 * i + 0.1, 0.9 + 0.5 * i, 2.2 + 0.4 * i)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * (base - 0.05f), 0f, h * (base + 0.2f), top, bottom, Shader.TileMode.CLAMP) })
        }
    }

    private fun ripple(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF04202b.toInt(), 0.55f to 0xFF0a5a6e.toInt(), 1f to 0xFF031a24.toInt()))
        val cx = w * 0.5f; val cy = h * 0.46f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (i in 1..14) {
            val r = i * w * 0.085f
            val fade = 1f - i / 15f
            p.strokeWidth = w * (0.012f + 0.006f * fade)
            p.color = ((0x66 * fade).toInt() shl 24) or 0x9FF0FF
            p.maskFilter = BlurMaskFilter(w * 0.004f * (1 + i * 0.25f), BlurMaskFilter.Blur.NORMAL)
            c.drawOval(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f, p)
        }
        fill(c, w, h, RadialGradient(cx, cy, w * 0.5f, 0x55BFF6FF, 0x00BFF6FF, Shader.TileMode.CLAMP))
    }

    private fun bokeh(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF0d1626.toInt(), 0.6f to 0xFF1c2f4a.toInt(), 1f to 0xFF2a2038.toInt()))
        val rnd = Random(11)
        val colors = intArrayOf(0xFF8CC63F.toInt(), 0xFF4fc3f7.toInt(), 0xFFffd166.toInt(), 0xFFf78fb3.toInt(), 0xFFffffff.toInt())
        for (i in 0 until 34) {
            val r = w * (0.03f + 0.11f * rnd.nextFloat() * rnd.nextFloat())
            val x = w * rnd.nextFloat(); val y = h * rnd.nextFloat()
            val col = colors[rnd.nextInt(colors.size)]
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (col and 0xFFFFFF) or (0x26 shl 24) }
            c.drawCircle(x, y, r, fillP)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.06f; color = (col and 0xFFFFFF) or (0x55 shl 24) }
            c.drawCircle(x, y, r, edge)
        }
    }

    private fun skyline(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF07101f.toInt(), 0.55f to 0xFF1b3a66.toInt(), 0.82f to 0xFFe98a5a.toInt(), 1f to 0xFFf6c27a.toInt()))
        val rnd = Random(5)
        val stars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        for (i in 0 until 60) c.drawCircle(w * rnd.nextFloat(), h * 0.45f * rnd.nextFloat(), 1f + 2f * rnd.nextFloat(), stars)
        for (layer in 0 until 2) {
            val base = h * (0.86f - 0.09f * (1 - layer))
            val body = Paint().apply { color = if (layer == 0) 0xFF1a2036.toInt() else 0xFF0b0e1a.toInt() }
            val win = Paint().apply { color = 0xFFffd98a.toInt() }
            var x = -w * 0.02f
            while (x < w) {
                val bw = w * (0.07f + 0.09f * rnd.nextFloat())
                val bh = h * (0.08f + (if (layer == 0) 0.16f else 0.1f) * rnd.nextFloat())
                c.drawRect(x, base - bh, x + bw, h.toFloat(), body)
                if (layer == 1) {
                    var wy = base - bh + h * 0.012f
                    while (wy < base - h * 0.01f) {
                        var wx = x + bw * 0.12f
                        while (wx < x + bw * 0.88f) { if (rnd.nextFloat() < 0.42f) c.drawRect(wx, wy, wx + bw * 0.1f, wy + h * 0.007f, win); wx += bw * 0.22f }
                        wy += h * 0.022f
                    }
                }
                x += bw + w * 0.004f
            }
        }
    }
}
