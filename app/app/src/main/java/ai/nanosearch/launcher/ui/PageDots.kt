package ai.nanosearch.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The page indicator, after Launcher3's: 6 dp dots with 4 dp gaps, the current page a dot twice as long that slides with the swipe, the others
 * at half opacity. Hidden while there is only one page.
 */
class PageDots(context: Context, private val color: Int) : View(context) {
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
        setMeasuredDimension((count * dot + (count - 1) * gap + dot).toInt() + paddingLeft + paddingRight, (24 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
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
