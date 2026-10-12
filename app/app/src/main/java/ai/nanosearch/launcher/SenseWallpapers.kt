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
 * The wallpapers of the Sense era, drawn in code at the screen's size: glossy and soft-focused, the way that period's phones looked. A burst of light,
 * silver chrome waves, a dusk over layered ridges, deep bokeh, ripples on dark water and a soft-focus meadow. All original artwork.
 */
object SenseWallpapers {
    val ALL = listOf(
        ClassicWallpapers.Paper("spectrum", "Spectrum"), ClassicWallpapers.Paper("cloudscape", "Cloudscape"),
        ClassicWallpapers.Paper("glow", "Glow"), ClassicWallpapers.Paper("chrome", "Chrome"), ClassicWallpapers.Paper("dusk", "Dusk"),
        ClassicWallpapers.Paper("bokeh", "Bokeh"), ClassicWallpapers.Paper("ripple", "Ripple"), ClassicWallpapers.Paper("meadow", "Meadow"),
    )

    fun has(id: String) = ALL.any { it.id == id }

    fun render(id: String, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        when (id) {
            "spectrum" -> spectrum(c, w, h)
            "cloudscape" -> cloudscape(c, w, h)
            "glow" -> glow(c, w, h)
            "chrome" -> chrome(c, w, h)
            "dusk" -> dusk(c, w, h)
            "meadow" -> meadow(c, w, h)
            "ripple" -> ripple(c, w, h)
            "bokeh" -> bokeh(c, w, h)
            else -> c.drawColor(0xFF111213.toInt())
        }
    }

    private fun darker(color: Int, f: Float) = android.graphics.Color.rgb((android.graphics.Color.red(color) * f).toInt(), (android.graphics.Color.green(color) * f).toInt(), (android.graphics.Color.blue(color) * f).toInt())

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

    /** A bright, soft-focus meadow: sky, a sun glare, soft clouds and hills that blur with distance. */
    private fun meadow(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF2273cf.toInt(), 0.35f to 0xFF63b0ec.toInt(), 0.62f to 0xFFcdeaf8.toInt(), 1f to 0xFFeaf6fb.toInt()))
        fill(c, w, h, RadialGradient(w * 0.78f, h * 0.26f, w * 0.75f, 0xCCFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP))
        val rnd = Random(7)
        val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); maskFilter = BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL) }
        for (i in 0 until 5) {
            val cx = w * (0.05f + 0.9f * rnd.nextFloat()); val cy = h * (0.1f + 0.3f * rnd.nextFloat()); val s = w * (0.1f + 0.08f * rnd.nextFloat())
            for (j in 0 until 5) c.drawCircle(cx + (j - 2) * s * 0.55f, cy + (if (j % 2 == 0) 0f else -s * 0.2f), s * (0.55f + 0.2f * rnd.nextFloat()), cloud)
        }
        val hills = listOf(Triple(0.62f, 0xFF86b9b0.toInt(), 0.012f), Triple(0.70f, 0xFF6cb04c.toInt(), 0.006f), Triple(0.80f, 0xFF3f9a2e.toInt(), 0f))
        hills.forEachIndexed { i, (base, color, blur) ->
            val path = ridge(w, h, base, 0.04f, 0.2 * i + 0.1, 0.9 + 0.5 * i, 2.2 + 0.4 * i)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, h * (base - 0.05f), 0f, h * (base + 0.2f), color, darker(color, 0.72f), Shader.TileMode.CLAMP)
                if (blur > 0f) maskFilter = BlurMaskFilter(w * blur, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawPath(path, paint)
        }
        val glint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 14) {
            val r = w * (0.012f + 0.03f * rnd.nextFloat())
            glint.color = 0x55FFFFFF; glint.maskFilter = BlurMaskFilter(r * 0.7f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(w * rnd.nextFloat(), h * (0.62f + 0.35f * rnd.nextFloat()), r, glint)
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

    /** Deep bokeh: soft out-of-focus lights of different sizes over a dark blue-violet gradient, a few of them sharper with a bright rim. */
    private fun bokeh(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF070d1f.toInt(), 0.55f to 0xFF14284a.toInt(), 1f to 0xFF2a1d3d.toInt()))
        fill(c, w, h, RadialGradient(w * 0.3f, h * 0.7f, h * 0.55f, 0x663FA7E8, 0x003FA7E8, Shader.TileMode.CLAMP))
        val rnd = Random(11)
        val colors = intArrayOf(0xFF8CC63F.toInt(), 0xFF4fc3f7.toInt(), 0xFFffd166.toInt(), 0xFFf78fb3.toInt(), 0xFFffffff.toInt(), 0xFF7aa2ff.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 30) {
            val r = w * (0.04f + 0.13f * rnd.nextFloat() * rnd.nextFloat())
            val col = colors[rnd.nextInt(colors.size)]
            p.style = Paint.Style.FILL; p.color = (col and 0xFFFFFF) or (0x2A shl 24); p.maskFilter = BlurMaskFilter(r * 0.35f, BlurMaskFilter.Blur.NORMAL)
            val x = w * rnd.nextFloat(); val y = h * rnd.nextFloat()
            c.drawCircle(x, y, r, p)
            if (i % 5 == 0) {
                p.maskFilter = null; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f; p.color = (col and 0xFFFFFF) or (0x66 shl 24)
                c.drawCircle(x, y, r, p)
            }
        }
    }

    /** A burst of light from the lower left: a bright core, soft rays fanning across the screen and a few drifting sparks. */
    private fun glow(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF020a1e.toInt(), 0.55f to 0xFF0a3a78.toInt(), 1f to 0xFF0b5ea0.toInt()))
        val ox = w * 0.18f; val oy = h * 0.86f
        val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(w * 0.012f, BlurMaskFilter.Blur.NORMAL) }
        val rnd = Random(3)
        for (i in 0 until 16) {
            val a = Math.toRadians(-100.0 + i * 8.5 + rnd.nextDouble() * 4)
            val spread = Math.toRadians(1.5 + rnd.nextDouble() * 3.5)
            val len = h * 1.6f
            val path = Path().apply {
                moveTo(ox, oy)
                lineTo(ox + (len * kotlin.math.cos(a - spread)).toFloat(), oy + (len * sin(a - spread)).toFloat())
                lineTo(ox + (len * kotlin.math.cos(a + spread)).toFloat(), oy + (len * sin(a + spread)).toFloat())
                close()
            }
            rays.shader = LinearGradient(ox, oy, ox + (len * kotlin.math.cos(a)).toFloat(), oy + (len * sin(a)).toFloat(), (0x30 + rnd.nextInt(0x18) shl 24) or 0x9FDFFF, 0x009FDFFF, Shader.TileMode.CLAMP)
            c.drawPath(path, rays)
        }
        fill(c, w, h, RadialGradient(ox, oy, h * 0.55f, intArrayOf(0xFFFFFFFF.toInt(), 0xCC8FDCFF.toInt(), 0x403FA7E8, 0x00000000), floatArrayOf(0f, 0.12f, 0.5f, 1f), Shader.TileMode.CLAMP))
        val spark = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 22) {
            val r = w * (0.004f + 0.012f * rnd.nextFloat())
            spark.color = 0x66FFFFFF; spark.maskFilter = BlurMaskFilter(r * 1.2f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(w * (0.1f + 0.9f * rnd.nextFloat()), h * (0.05f + 0.8f * rnd.nextFloat()), r, spark)
        }
        fill(c, w, h, RadialGradient(w / 2f, h / 2f, h * 0.85f, 0x00000000, 0x77000000, Shader.TileMode.CLAMP))
    }

    /** Silver chrome: stacked glossy ribbons with a bright edge along the top of each, on charcoal. */
    private fun chrome(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF30353a.toInt(), 0.5f to 0xFF15181b.toInt(), 1f to 0xFF08090a.toInt()))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 7) {
            val base = 0.2f + i * 0.1f
            val top = ridge(w, h, base, 0.07f, 0.13 * i, 0.9 + 0.1 * i, 1.7)
            val thick = h * (0.16f + 0.01f * i)
            val band = Path().apply {
                addPath(top)
                val m = android.graphics.Matrix(); m.setTranslate(0f, thick)
                val under = Path(top); under.transform(m)
                op(under, Path.Op.DIFFERENCE)
            }
            p.style = Paint.Style.FILL; p.maskFilter = null
            p.shader = LinearGradient(0f, h * (base - 0.08f), 0f, h * base + thick, intArrayOf(0xFFF2F5F7.toInt(), 0xFF9aa2a9.toInt(), 0xFF3a4046.toInt()), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            p.alpha = 235
            c.drawPath(band, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.004f; p.color = 0xCCFFFFFF.toInt(); p.alpha = 200
            val edge = Path(top)
            c.save(); c.clipPath(band); c.drawPath(edge, p); c.restore()
        }
        fill(c, w, h, RadialGradient(w / 2f, h * 0.4f, h * 0.9f, 0x00000000, 0x88000000.toInt(), Shader.TileMode.CLAMP))
    }

    /** The full spectrum, violet to red: ribbons of light that cross each other over a dark ground, each one carrying every colour. */
    private fun spectrum(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF03040a.toInt(), 1f to 0xFF0a0c18.toInt()))
        val rainbow = intArrayOf(0xFFB02BFF.toInt(), 0xFF4655FF.toInt(), 0xFF1E9BFF.toInt(), 0xFF2EDB6C.toInt(), 0xFFFFE23A.toInt(), 0xFFFF8A1F.toInt(), 0xFFFF2A3D.toInt())
        val screen = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN) // brightens where they cross without blowing out to white
        for (i in 0 until 13) {
            val t = i / 12f
            val path = Path().apply {
                moveTo(-w * 0.2f, h * (1.08f - 0.085f * i))
                cubicTo(w * 0.32f, h * (0.12f + 0.07f * i), w * 0.58f, h * (0.98f - 0.06f * i), w * 1.2f, h * (0.0f + 0.075f * i))
            }
            val colors = IntArray(rainbow.size + 1) { rainbow[(it + i) % rainbow.size] }
            val sh = LinearGradient(w * 0.1f, h.toFloat(), w * 0.9f, 0f, colors, null, Shader.TileMode.MIRROR)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; shader = sh; xfermode = screen
                strokeWidth = w * (0.03f + 0.05f * (1f - t)); alpha = 95; maskFilter = BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL)
            })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; shader = sh; xfermode = screen
                strokeWidth = w * 0.007f; alpha = 235; maskFilter = BlurMaskFilter(w * 0.003f, BlurMaskFilter.Blur.NORMAL)
            })
        }
        val bloom = Paint().apply { xfermode = screen }
        bloom.shader = RadialGradient(w * 0.1f, h * 0.9f, h * 0.34f, 0x55FF6A2E, 0x00FF6A2E, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bloom)
        bloom.shader = RadialGradient(w * 0.95f, h * 0.1f, h * 0.3f, 0x443B6BFF, 0x003B6BFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bloom)
        fill(c, w, h, RadialGradient(w / 2f, h / 2f, h * 0.85f, 0x00000000, 0x88000000.toInt(), Shader.TileMode.CLAMP))
    }

    /** Cumulus: a cluster of soft overlapping puffs, a cool shade under each and the lit top over it. */
    private fun cluster(c: Canvas, w: Int, cx: Float, cy: Float, rw: Float, rh: Float, puffs: Int, rnd: Random, lit: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(puffs) {
            val x = cx + rw * (rnd.nextFloat() * 2f - 1f)
            val y = cy + rh * (rnd.nextFloat() * 2f - 1f) * 0.45f
            val r = rh * (0.55f + 0.9f * rnd.nextFloat())
            p.maskFilter = BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL)
            p.color = 0x773A79A0; c.drawCircle(x, y + r * 0.3f, r, p)
            p.color = lit; c.drawCircle(x, y - r * 0.05f, r * 0.92f, p)
        }
    }

    /** Cloud banks over a far blue mountain range, with mist in the valley and a bright bank of fog in front: a soft, photographic scene. */
    private fun cloudscape(c: Canvas, w: Int, h: Int) {
        fill(c, w, h, vgrad(h, 0f to 0xFF0a4672.toInt(), 0.30f to 0xFF2a86b5.toInt(), 0.56f to 0xFFa5d6e6.toInt(), 1f to 0xFF2a7da0.toInt()))
        val rnd = Random(21)
        for (i in 0 until 4) cluster(c, w, w * (0.12f + 0.26f * i), h * (0.10f + 0.04f * (i % 2)), w * 0.3f, h * 0.05f, 12, rnd, 0xAAF4FBFF.toInt())
        for (i in 0 until 3) cluster(c, w, w * (0.1f + 0.4f * i), h * (0.30f + 0.05f * (i % 2)), w * 0.35f, h * 0.06f, 14, rnd, 0xCCFFFFFF.toInt())
        val far = ridge(w, h, 0.61f, 0.06f, 0.31, 1.1, 3.3)
        c.drawPath(far, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.52f, 0f, h * 0.68f, 0xFF1f6286.toInt(), 0xFF4a93b3.toInt(), Shader.TileMode.CLAMP); maskFilter = BlurMaskFilter(w * 0.004f, BlurMaskFilter.Blur.NORMAL) })
        val near = ridge(w, h, 0.68f, 0.05f, 0.77, 0.8, 2.6)
        c.drawPath(near, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.6f, 0f, h * 0.76f, 0xFF0f3f5b.toInt(), 0xFF2a7194.toInt(), Shader.TileMode.CLAMP) })
        val soft = Paint(Paint.ANTI_ALIAS_FLAG)
        soft.maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL)
        soft.color = 0xAAE6F6FC.toInt()
        for (k in 0 until 6) c.drawOval(w * (k * 0.2f - 0.1f), h * 0.68f, w * (k * 0.2f + 0.3f), h * 0.735f, soft)
        for (i in 0 until 5) cluster(c, w, w * (0.05f + 0.22f * i), h * (0.84f + 0.03f * (i % 2)), w * 0.28f, h * 0.07f, 10, rnd, 0xEEF2FBFE.toInt())
        fill(c, w, h, vgrad(h, 0f to 0x00000000, 0.75f to 0x00000000, 1f to 0x552a7da0))
    }
}
