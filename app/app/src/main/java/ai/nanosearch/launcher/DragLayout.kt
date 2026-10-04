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
    /** Only for the home screen ([up] = true): called when a mostly-downward swipe is let go after a decent pull or a quick flick. */
    var onPullDown: (() -> Unit)? = null
    var canPullDown: () -> Boolean = { true }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val pullDistance = 96 * context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var pulling = false
    private var tracker: VelocityTracker? = null

    private fun distance(e: MotionEvent) = if (up) downY - e.rawY else e.rawY - downY

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                pulling = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                // A clearly vertical downward swipe is a pull on the notification panel; sideways paging is left alone.
                if (up && onPullDown != null && !dragging && !pulling) {
                    val dy = e.rawY - downY
                    if (dy > slop * 2 && dy > Math.abs(e.rawX - downX) * 1.5f && canPullDown()) { pulling = true; return true }
                }
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
        if (!dragging && !pulling) super.onTouchEvent(e)
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
                if (pulling) {
                    pulling = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && (e.rawY - downY > pullDistance || vy > 1500f)) onPullDown?.invoke()
                    return true
                }
                onRelease(vy, was)
            }
        }
        return true
    }
}
