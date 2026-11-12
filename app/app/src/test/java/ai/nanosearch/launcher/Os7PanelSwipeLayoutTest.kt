package ai.nanosearch.launcher

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Os7PanelSwipeLayoutTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun event(view: View, action: Int, x: Int, y: Int) {
        val density = context.resources.displayMetrics.density
        MotionEvent.obtain(0, 100, action, x * density, y * density, 0).also {
            view.dispatchTouchEvent(it); it.recycle()
        }
    }

    @Test fun horizontalSwipeCancelsChildAndPagesExactlyOnce() {
        val pages = mutableListOf<Int>()
        val events = mutableListOf<Int>()
        val area = Os7PanelSwipeLayout(context) { pages.add(it) }
        area.addView(object : View(context) {
            override fun onTouchEvent(e: MotionEvent): Boolean { events.add(e.actionMasked); return true }
        }, FrameLayout.LayoutParams(-1, -1))
        val size = (600 * context.resources.displayMetrics.density).toInt()
        area.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        area.layout(0, 0, size, size)
        event(area, MotionEvent.ACTION_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_MOVE, 40, 35)
        event(area, MotionEvent.ACTION_UP, 20, 35)
        assertEquals(listOf(1), pages)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
    }

    @Test fun verticalTapCanceledAndMultiTouchGesturesDoNotPage() {
        val pages = mutableListOf<Int>()
        val area = Os7PanelSwipeLayout(context) { pages.add(it) }
        event(area, MotionEvent.ACTION_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_MOVE, 240, 250)
        event(area, MotionEvent.ACTION_UP, 240, 250)
        event(area, MotionEvent.ACTION_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_UP, 250, 30)
        event(area, MotionEvent.ACTION_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_MOVE, 20, 30)
        event(area, MotionEvent.ACTION_CANCEL, 20, 30)
        event(area, MotionEvent.ACTION_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_POINTER_DOWN, 250, 30)
        event(area, MotionEvent.ACTION_MOVE, 20, 30)
        event(area, MotionEvent.ACTION_UP, 20, 30)
        assertTrue(pages.isEmpty())
    }
}
