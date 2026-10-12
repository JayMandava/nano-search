package ai.nanosearch.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import kotlin.random.Random

/** The materials of the Sense look, drawn in code: perforated carbon, brushed metal and smoked glass. */
object SenseMaterial {
    private var dotsTile: Bitmap? = null
    private var brushTile: Bitmap? = null

    /** A repeating tile of small embossed dots in a staggered grid, the "dotted carbon" of the dock. */
    fun dots(context: Context): BitmapShader {
        val d = context.resources.displayMetrics.density
        val tile = dotsTile ?: run {
            val t = (8 * d).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for ((x, y) in listOf(t * 0.25f to t * 0.25f, t * 0.75f to t * 0.75f)) {
                p.color = 0x99000000.toInt(); c.drawCircle(x, y + 0.6f * d, 1.5f * d, p) // the hollow
                p.color = 0x2EFFFFFF; c.drawCircle(x, y - 0.5f * d, 1.3f * d, p)          // the lit lip
            }
            bmp.also { dotsTile = it }
        }
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Fine horizontal streaks, light and dark, like brushed aluminium. */
    fun brushed(context: Context): BitmapShader {
        val tile = brushTile ?: run {
            val bmp = Bitmap.createBitmap(96, 24, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val rnd = Random(5)
            val p = Paint()
            for (y in 0 until 24) {
                val light = rnd.nextBoolean()
                p.color = (if (light) 0xFFFFFFFF else 0xFF000000).toInt()
                p.alpha = rnd.nextInt(10, 46)
                c.drawRect(0f, y.toFloat(), 96f, y + 1f, p)
            }
            bmp.also { brushTile = it }
        }
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

/** Smoked glass with a crisp sheen across its upper half and a bright rim. Casts a shadow when given an elevation. */
class SenseGloss(context: Context, private val radiusDp: Int) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private val r = radiusDp * d
    private val rect = RectF()
    private val clip = Path()
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(b: Rect) {
        rect.set(b); clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        base.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), intArrayOf(0xF2484C51.toInt(), 0xF21C1E21.toInt(), 0xF20C0D0F.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.centerY().toFloat(), 0x3AFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), 0xCCFFFFFF.toInt(), 0x44FFFFFF, Shader.TileMode.CLAMP)
        lip.shader = LinearGradient(0f, b.bottom - 3 * d, 0f, b.bottom.toFloat(), 0x00000000, 0x55000000, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        c.save(); c.clipPath(clip)
        c.drawRect(rect, base)
        c.drawRect(rect.left, rect.top, rect.right, rect.centerY(), sheen)
        c.drawRect(rect.left, rect.bottom - 3 * d, rect.right, rect.bottom, lip)
        c.restore()
        val inset = rim.strokeWidth / 2
        c.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, rim)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, r); outline.alpha = 0.85f }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A brushed-metal sheen to lay over a dark bar. */
class BrushedMetal(context: Context, private val alpha: Int = 70) : Drawable() {
    private val paint = Paint().apply { shader = SenseMaterial.brushed(context) }
    override fun draw(c: Canvas) { paint.alpha = alpha; c.drawRect(bounds, paint) }
    override fun setAlpha(a: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
