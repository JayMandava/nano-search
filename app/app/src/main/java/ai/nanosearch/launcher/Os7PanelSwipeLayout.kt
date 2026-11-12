package ai.nanosearch.launcher

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/** Own paging at the tray level, including empty panels and unused space below short grids. */
internal class Os7PanelSwipeLayout(context: Context, private val page: (Int) -> Unit) : FrameLayout(context) {
    private val distance = 80 * resources.displayMetrics.density
    private var x = 0f
    private var y = 0f
    private var paging = false
    private var watching = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.x; y = event.y
                paging = false; watching = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> watching = false
            MotionEvent.ACTION_MOVE -> if (watching && !paging && horizontal(event)) {
                paging = true
                // Stop the pressed grid item/long press before taking over its gesture.
                MotionEvent.obtain(event).also {
                    it.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        if (paging) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val commit = event.actionMasked == MotionEvent.ACTION_UP && watching && horizontal(event)
                paging = false; watching = false
                if (commit) page(if (event.x < x) 1 else -1)
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) watching = false
        // Empty text is not clickable, but the tray must retain its DOWN to receive the swipe.
        return handled || watching
    }

    private fun horizontal(event: MotionEvent): Boolean {
        val dx = abs(event.x - x)
        return dx > distance && dx > abs(event.y - y) * 1.5f
    }
}
