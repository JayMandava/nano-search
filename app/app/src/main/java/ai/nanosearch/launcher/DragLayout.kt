package ai.nanosearch.launcher

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * A layout that turns a vertical drag into callbacks. [up] = true tracks upward drags (the home screen pulling the
 * app drawer up); false tracks downward drags (the drawer being pulled down), which only start when [canStart] allows,
 * so the list inside can still scroll first.
 */
class DragLayout(context: Context, private val up: Boolean) : FrameLayout(context) {
    /** Distance dragged in the tracked direction, in pixels (always >= 0 once dragging). */
    var onDrag: (Float) -> Unit = {}
    /** Called on release with the vertical velocity (px/s, negative = upward) and whether a drag happened. */
    var onRelease: (velocityY: Float, dragged: Boolean) -> Unit = { _, _ -> }
    var canStart: () -> Boolean = { true }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    private fun distance(e: MotionEvent) = if (up) downY - e.rawY else e.rawY - downY

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging && distance(e) > slop && canStart()) {
                    dragging = true
                    downY = e.rawY - (if (up) -slop else slop) // start the drag at zero rather than jumping by the slop
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        // Let the view's own click and long-press handling see the touch until a drag begins.
        if (!dragging) super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && distance(e) > slop && canStart()) {
                    dragging = true
                    MotionEvent.obtain(e).also { it.action = MotionEvent.ACTION_CANCEL; super.onTouchEvent(it); it.recycle() }
                }
                if (dragging) onDrag(distance(e).coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                val was = dragging
                dragging = false
                onRelease(vy, was)
            }
        }
        return true
    }
}
