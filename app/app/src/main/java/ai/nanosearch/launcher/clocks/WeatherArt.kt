package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.Weather
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small weather pictures drawn in code: a sun with turning rays, a moon, clouds, rain, snow, fog and lightning. [t] runs 0..1 and repeats, for the motion. */
class WeatherArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        p.style = Paint.Style.FILL
        p.shader = android.graphics.RadialGradient(cx, cy, r * 0.95f, 0x66FFD54F, 0x00FFD54F, android.graphics.Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r * 0.95f, p)
        p.shader = android.graphics.RadialGradient(cx - r * 0.12f, cy - r * 0.14f, r * 0.5f, 0xFFFFF3A0.toInt(), 0xFFFF9F1C.toInt(), android.graphics.Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r * 0.42f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.14f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFFFC93C.toInt()
        for (i in 0 until 8) {
            val a = (i / 8f + t) * 2 * PI
            c.drawLine(cx + (r * 0.62f * cos(a)).toFloat(), cy + (r * 0.62f * sin(a)).toFloat(), cx + (r * 0.92f * cos(a)).toFloat(), cy + (r * 0.92f * sin(a)).toFloat(), p)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val disc = Path().apply { addCircle(cx, cy, r * 0.62f, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.3f, cy - r * 0.18f, r * 0.55f, Path.Direction.CW) }
        disc.op(bite, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL; p.color = 0xFFE8ECF4.toInt(); c.drawPath(disc, p)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, r: Float, color: Int = 0xFFF4F6F8.toInt(), drift: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color
        // lit from above: white on top, a cool grey underneath
        p.shader = android.graphics.LinearGradient(0f, cy - r * 0.55f, 0f, cy + r * 0.42f, color, (color and 0xFF000000.toInt()) or ((((color shr 16) and 0xFF) * 8 / 10) shl 16) or ((((color shr 8) and 0xFF) * 85 / 100) shl 8) or (((color and 0xFF) * 92) / 100), android.graphics.Shader.TileMode.CLAMP)
        val x = cx + drift
        c.drawCircle(x - r * 0.32f, cy + r * 0.05f, r * 0.34f, p)
        c.drawCircle(x + r * 0.06f, cy - r * 0.14f, r * 0.42f, p)
        c.drawCircle(x + r * 0.42f, cy + r * 0.08f, r * 0.3f, p)
        c.drawRoundRect(x - r * 0.66f, cy + r * 0.02f, x + r * 0.72f, cy + r * 0.4f, r * 0.2f, r * 0.2f, p)
        p.shader = null
    }

    fun draw(c: Canvas, sky: Weather.Sky, day: Boolean, cx: Float, cy: Float, size: Float, t: Float) {
        val r = size / 2f
        val sway = (sin(t * 2 * PI) * r * 0.06f).toFloat()
        when (sky) {
            Weather.Sky.CLEAR -> if (day) sun(c, cx, cy, r, t) else moon(c, cx, cy, r)
            Weather.Sky.PARTLY -> { if (day) sun(c, cx - r * 0.25f, cy - r * 0.2f, r * 0.8f, t) else moon(c, cx - r * 0.25f, cy - r * 0.2f, r * 0.8f); cloud(c, cx + r * 0.1f, cy + r * 0.2f, r * 0.85f, drift = sway) }
            Weather.Sky.CLOUDY -> { cloud(c, cx - r * 0.1f, cy - r * 0.1f, r * 0.7f, 0xFFB8BFC6.toInt()); cloud(c, cx + r * 0.1f, cy + r * 0.15f, r * 0.85f, drift = sway) }
            Weather.Sky.FOG -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFD5DADF.toInt()
                for (i in -1..1) c.drawLine(cx - r * 0.7f + sway * i, cy + i * r * 0.4f, cx + r * 0.7f + sway * i, cy + i * r * 0.4f, p)
            }
            Weather.Sky.RAIN, Weather.Sky.STORM, Weather.Sky.SNOW -> {
                cloud(c, cx, cy - r * 0.25f, r * 0.85f, 0xFFC3CAD1.toInt())
                for (i in 0 until 3) {
                    val phase = (t + i / 3f) % 1f
                    val x = cx - r * 0.4f + i * r * 0.4f
                    val y = cy + r * 0.2f + phase * r * 0.8f
                    p.alpha = (255 * (1f - phase * 0.6f)).toInt()
                    if (sky == Weather.Sky.SNOW) { p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, r * 0.1f, p) }
                    else { p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF5BB8FF.toInt(); c.drawLine(x, y, x - r * 0.1f, y + r * 0.25f, p) }
                    p.alpha = 255
                }
                if (sky == Weather.Sky.STORM) {
                    p.style = Paint.Style.FILL; p.color = 0xFFFFD84A.toInt()
                    c.drawPath(Path().apply { moveTo(cx + r * 0.05f, cy - r * 0.1f); lineTo(cx - r * 0.2f, cy + r * 0.45f); lineTo(cx, cy + r * 0.4f); lineTo(cx - r * 0.12f, cy + r * 0.95f); lineTo(cx + r * 0.3f, cy + r * 0.2f); lineTo(cx + r * 0.1f, cy + r * 0.25f); close() }, p)
                }
            }
        }
    }
}
