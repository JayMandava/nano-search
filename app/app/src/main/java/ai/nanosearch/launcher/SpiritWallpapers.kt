package ai.nanosearch.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max
import kotlin.random.Random

/**
 * The wallpapers, the same in every style: woven carbon, brushed steel and five more metals (gold, rose gold, copper, bronze and platinum), all drawn here in code
 * at whatever size the screen is. All original artwork.
 */
object SpiritWallpapers {
    class Paper(val id: String, val name: String)

    val ALL = listOf(
        Paper("carbon", "Carbon"), Paper("steel", "Steel"), Paper("gold", "Gold"), Paper("rosegold", "Rose Gold"),
        Paper("copper", "Copper"), Paper("bronze", "Bronze"), Paper("platinum", "Platinum"),
    )

    fun render(id: String, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        when (id) {
            "steel" -> steel(c, w, h)
            "gold" -> metal(c, w, h, 0xFF3E2C06, 0xFFB8892B, 0xFFF7DE86, 0xFFC9972E, 0xFF6B4D0F, 0xFFEBC862, 0xFF2E2005, 1)
            "rosegold" -> metal(c, w, h, 0xFF4A2A2E, 0xFFC08A84, 0xFFF6CFC6, 0xFFB77C78, 0xFF6E3D42, 0xFFE3AEA5, 0xFF3A2024, 2)
            "copper" -> metal(c, w, h, 0xFF2E1408, 0xFFB4622D, 0xFFF2A872, 0xFFA4511F, 0xFF5A2810, 0xFFDB8441, 0xFF241006, 3)
            "bronze" -> metal(c, w, h, 0xFF241808, 0xFF8A6234, 0xFFD0A86C, 0xFF7A5428, 0xFF3D2A12, 0xFFB98E50, 0xFF1C1206, 4)
            "platinum" -> metal(c, w, h, 0xFF2C2E32, 0xFFB4B9C0, 0xFFF4F6F9, 0xFFA5AAB2, 0xFF50545A, 0xFFE0E3E7, 0xFF24262A, 5)
            else -> carbon(c, w, h) // "carbon", and the older wallpapers that are no longer offered
        }
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg colors: Int) =
        LinearGradient(x0, y0, x1, y1, colors, null, Shader.TileMode.CLAMP)

    /**
     * A sheet of brushed metal, built the way Steel is: bands of light and dark standing side by side down the screen, fine horizontal brushing across them,
     * and light from above that falls away towards the foot. The seven tones go from deep shadow to the brightest highlight and back.
     */
    private fun metal(c: Canvas, w: Int, h: Int, t0: Long, t1: Long, t2: Long, t3: Long, t4: Long, t5: Long, t6: Long, seed: Int) {
        val tones = longArrayOf(t0, t1, t2, t3, t4, t5, t6).map { it.toInt() }.toIntArray()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, tones, floatArrayOf(0f, 0.17f, 0.32f, 0.5f, 0.66f, 0.84f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        val rnd = Random(seed)
        p.strokeWidth = 1f
        repeat(h) { p.color = if (rnd.nextBoolean()) 0x0AFFFFFF else 0x0C000000; val y = rnd.nextFloat() * h; c.drawLine(0f, y, w.toFloat(), y, p) }
        p.shader = lin(0f, 0f, 0f, h.toFloat(), 0x33FFFFFF, 0x00FFFFFF, 0x55000000)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

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
}
