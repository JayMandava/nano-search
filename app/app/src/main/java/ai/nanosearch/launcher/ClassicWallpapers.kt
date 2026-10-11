package ai.nanosearch.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.max
import kotlin.random.Random

/**
 * A set of wallpapers for the Classic style, drawn here in code at whatever size the screen is: flowing light on deep blues and violets, woven carbon,
 * brushed steel, aurora glows, embers and a pale glacier for the light theme. All original artwork.
 */
object ClassicWallpapers {
    class Paper(val id: String, val name: String)

    val ALL = listOf(
        Paper("cobalt", "Cobalt"), Paper("violet", "Violet"), Paper("carbon", "Carbon"), Paper("steel", "Steel"),
        Paper("aurora", "Aurora"), Paper("ember", "Ember"), Paper("graphite", "Graphite"), Paper("glacier", "Glacier"),
    )

    fun render(id: String, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        when (id) {
            "cobalt" -> ribbons(c, w, h, 0xFF030b22.toInt(), 0xFF0a4aa8.toInt(), 0xFF2f8fe8.toInt(), 0xFF7fe3ff.toInt(), 1)
            "violet" -> ribbons(c, w, h, 0xFF0b0720.toInt(), 0xFF3a1c8c.toInt(), 0xFF8f3fd0.toInt(), 0xFFff8fe0.toInt(), 2)
            "carbon" -> carbon(c, w, h)
            "steel" -> steel(c, w, h)
            "aurora" -> aurora(c, w, h)
            "ember" -> ribbons(c, w, h, 0xFF0c0605.toInt(), 0xFF5a1608.toInt(), 0xFFd9480f.toInt(), 0xFFffc060.toInt(), 3)
            "graphite" -> graphite(c, w, h)
            "glacier" -> glacier(c, w, h)
            else -> c.drawColor(0xFF111213.toInt())
        }
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg colors: Int) =
        LinearGradient(x0, y0, x1, y1, colors, null, Shader.TileMode.CLAMP)

    /** Deep gradient with a few ribbons of light sweeping up from the lower corner, each a wide soft glow around a bright thin core. */
    private fun ribbons(c: Canvas, w: Int, h: Int, top: Int, mid: Int, glow: Int, core: Int, seed: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = lin(0f, 0f, 0f, h.toFloat(), top, mid, top)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.5f, h * 0.62f, h * 0.55f, (glow and 0x00FFFFFF) or 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val rnd = Random(seed)
        // one broad, very soft sweep under the ribbons
        val sweep = Path().apply {
            moveTo(w * 1.2f, h * 1.05f)
            cubicTo(w * 0.8f, h * 0.76f, w * 0.38f, h * 0.9f, -w * 0.15f, h * 0.5f)
        }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.maskFilter = BlurMaskFilter(w * 0.16f, BlurMaskFilter.Blur.NORMAL); p.strokeWidth = w * 0.3f; p.color = (glow and 0x00FFFFFF) or 0x4A000000; c.drawPath(sweep, p)
        for (i in 0 until 5) {
            val k = i / 4f
            val path = Path().apply {
                moveTo(w * (1.2f - 0.12f * k), h * (1.04f + 0.04f * k))
                cubicTo(w * (0.8f - 0.1f * k), h * (0.76f - 0.045f * k + rnd.nextFloat() * 0.02f), w * (0.38f + 0.12f * k), h * (0.9f - 0.1f * k), -w * 0.15f, h * (0.5f - 0.08f * k))
            }
            p.maskFilter = BlurMaskFilter(w * (0.06f - 0.008f * k), BlurMaskFilter.Blur.NORMAL)
            p.strokeWidth = w * (0.1f - 0.012f * k); p.color = (glow and 0x00FFFFFF) or ((0x70 - i * 9) shl 24); c.drawPath(path, p)
            p.maskFilter = BlurMaskFilter(w * 0.01f, BlurMaskFilter.Blur.NORMAL)
            p.strokeWidth = w * (0.016f - 0.002f * k); p.color = (core and 0x00FFFFFF) or ((0xF0 - i * 26) shl 24); c.drawPath(path, p)
            p.maskFilter = null
        }
        // a faint haze of stars
        p.style = Paint.Style.FILL
        repeat(60) { p.color = 0x22FFFFFF; c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.6f, 1f + rnd.nextFloat() * 1.8f, p) }
    }

    /** Woven carbon fibre: a fine diamond weave, lit from the top with a cool glow along the bottom edge. */
    private fun carbon(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = lin(0f, 0f, 0f, h.toFloat(), 0xFF1d1f22.toInt(), 0xFF0a0b0c.toInt())
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        val cell = max(6f, w / 90f)
        var row = 0; var y = 0f
        while (y < h) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < w) {
                p.color = 0x14FFFFFF; c.drawRect(x, y, x + cell, y + cell / 2, p)
                p.color = 0x22000000; c.drawRect(x, y + cell / 2, x + cell, y + cell, p)
                x += cell * 2
            }
            y += cell; row++
        }
        p.shader = lin(0f, 0f, 0f, h * 0.45f, 0x30FFFFFF, 0x00FFFFFF); c.drawRect(0f, 0f, w.toFloat(), h * 0.45f, p)
        p.shader = lin(0f, h * 0.7f, 0f, h.toFloat(), 0x00000000, 0x6600a3e0); c.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), p)
    }

    /** Brushed steel: a cool grey sheet with thousands of fine horizontal strokes and a broad soft highlight. */
    private fun steel(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0xFF50555b.toInt(), 0xFFa9aeb4.toInt(), 0xFF6a7076.toInt(), 0xFFbfc4c9.toInt(), 0xFF4a4f55.toInt()), floatArrayOf(0f, 0.28f, 0.5f, 0.74f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        val rnd = Random(7)
        p.strokeWidth = 1f
        repeat(h) { p.color = if (rnd.nextBoolean()) 0x0AFFFFFF else 0x0C000000; val y = rnd.nextFloat() * h; c.drawLine(0f, y, w.toFloat(), y, p) }
        p.shader = lin(0f, 0f, 0f, h.toFloat(), 0x33FFFFFF, 0x00FFFFFF, 0x55000000); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    /** Large soft glows of green, blue and violet drifting over a dark teal ground. */
    private fun aurora(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = lin(0f, 0f, 0f, h.toFloat(), 0xFF02111a.toInt(), 0xFF071a2a.toInt(), 0xFF0a0716.toInt()); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val blobs = listOf(Triple(0.25f to 0.30f, 0.55f, 0x6620d6a0), Triple(0.8f to 0.45f, 0.5f, 0x663a7bff), Triple(0.4f to 0.72f, 0.6f, 0x558f4bff), Triple(0.9f to 0.92f, 0.4f, 0x4420c8ff))
        for ((pos, r, col) in blobs) {
            p.shader = RadialGradient(w * pos.first, h * pos.second, w * r, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        p.shader = null
        val rnd = Random(11)
        repeat(90) { p.color = 0x28FFFFFF; c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, 1f + rnd.nextFloat() * 1.6f, p) }
    }

    /** Charcoal with a soft vignette and two pale diagonal beams. */
    private fun graphite(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w * 0.5f, h * 0.35f, h * 0.8f, 0xFF30343a.toInt(), 0xFF08090a.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        for ((x, a) in listOf(0.2f to 0x22, 0.62f to 0x18)) {
            val beam = Path().apply { moveTo(w * x, -h * 0.05f); lineTo(w * (x + 0.22f), -h * 0.05f); lineTo(w * (x + 0.95f), h * 1.05f); lineTo(w * (x + 0.45f), h * 1.05f); close() }
            p.maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL); p.color = a shl 24 or 0xFFFFFF; c.drawPath(beam, p); p.maskFilter = null
        }
    }

    /** The light one: white to pale blue with a few overlapping translucent waves. */
    private fun glacier(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = lin(0f, 0f, 0f, h.toFloat(), 0xFFf6fafd.toInt(), 0xFFd3e6f4.toInt(), 0xFFa9cbe6.toInt()); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        for (i in 0 until 5) {
            val baseY = h * (0.52f + i * 0.1f)
            val wave = Path().apply {
                moveTo(0f, h.toFloat()); lineTo(0f, baseY)
                cubicTo(w * 0.3f, baseY - h * 0.07f, w * 0.6f, baseY + h * 0.06f, w.toFloat(), baseY - h * 0.03f)
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.shader = lin(0f, baseY - h * 0.07f, 0f, h.toFloat(), (0x40 + i * 6 shl 24) or 0xFFFFFF, (0x30 shl 24) or 0x5aa0d8); c.drawPath(wave, p)
        }
        p.shader = null
    }
}
