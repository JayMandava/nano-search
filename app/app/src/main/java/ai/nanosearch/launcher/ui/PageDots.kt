package ai.nanosearch.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The page indicator, after Launcher3's: 6 dp dots with 4 dp gaps, the current page a dot twice as long that slides with the swipe, the others
 * at half opacity. Hidden while there is only one page. With [arc] (the Sense style) the dots sit along a shallow curve and the current page glows in [accent].
 */
class PageDots(context: Context, private val color: Int, private val arc: Boolean = false, private val accent: Int = color) : View(context) {
    private val density = resources.displayMetrics.density
    private val dot = 6 * density
    private val gap = 4 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var count = 1
    private var position = 0f

    fun setCount(n: Int) { count = n.coerceAtLeast(1); visibility = if (count > 1) VISIBLE else INVISIBLE; requestLayout(); invalidate() }

    /** Current position as page + fraction of the swipe to the next one. */
    fun setPosition(p: Float) { position = p; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (arc) { setMeasuredDimension((count * 14 * density + 18 * density).toInt() + paddingLeft + paddingRight, (30 * density).toInt()); return }
        setMeasuredDimension((count * dot + (count - 1) * gap + dot).toInt() + paddingLeft + paddingRight, (24 * density).toInt())
    }

    private fun drawArc(canvas: Canvas) {
        val step = 14 * density
        val startX = (width - (count - 1) * step) / 2f
        val sag = 5 * density
        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i / (count - 1f)
            val x = startX + i * step
            val y = height / 2f + sag / 2 - sag * (1f - (2 * t - 1f) * (2 * t - 1f)) + sag / 2
            val near = (1f - kotlin.math.abs(i - position)).coerceIn(0f, 1f)
            val r = (2.2f + 1.8f * near) * density
            if (near > 0f) {
                paint.color = accent; paint.alpha = (90 * near).toInt()
                canvas.drawCircle(x, y, r * 2.1f, paint)
            }
            paint.color = if (near > 0.5f) accent else color
            paint.alpha = (110 + 145 * near).toInt()
            canvas.drawCircle(x, y, r, paint)
            paint.color = 0xFFFFFFFF.toInt(); paint.alpha = (150 * near).toInt()
            canvas.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.35f, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (arc) { drawArc(canvas); return }
        val cy = height / 2f
        val startX = (width - (count * dot + (count - 1) * gap + dot)) / 2f
        // the dots keep their places; the active pill stretches from the dot it leaves to the dot it reaches
        paint.color = color
        paint.alpha = 128
        for (i in 0 until count) {
            val x = startX + i * (dot + gap) + (if (i > position) dot else 0f)
            rect.set(x, cy - dot / 2, x + dot, cy + dot / 2)
            canvas.drawRoundRect(rect, dot / 2, dot / 2, paint)
        }
        paint.alpha = 255
        val first = position.toInt().coerceIn(0, count - 1)
        val frac = position - first
        val left = startX + first * (dot + gap) + frac * (dot + gap)
        rect.set(left, cy - dot / 2, left + 2 * dot, cy + dot / 2)
        canvas.drawRoundRect(rect, dot / 2, dot / 2, paint)
    }
}
