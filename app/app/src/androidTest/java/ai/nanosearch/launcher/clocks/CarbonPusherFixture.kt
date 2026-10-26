package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LongPressFrame
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.min

/** All Clock launches are captured here; no external activity or launcher is changed by these tests. */
internal class PusherClockContext(base: Context) : IsolatedClockContext(base, "pusher-test-${UUID.randomUUID()}") {
    val clockLaunches = mutableListOf<Intent>()
    override fun startActivity(intent: Intent) { clockLaunches += Intent(intent) }
    override fun startActivity(intent: Intent, options: Bundle?) { clockLaunches += Intent(intent) }
}

internal class CarbonPusherScene(activity: Activity, factory: (Context) -> ClockFaceView, heightDp: Int = 360) {
    val context = PusherClockContext(activity)
    val view = factory(context)
    val scroll = ScrollView(activity)
    val frame = LongPressFrame(activity)
    var longPresses = 0
        private set
    init {
        val density = activity.resources.displayMetrics.density
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(activity), LinearLayout.LayoutParams(-1, (80 * density).toInt()))
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        frame.onLongPress = { longPresses++ }
        column.addView(frame, LinearLayout.LayoutParams(-1, (heightDp * density).toInt()))
        column.addView(View(activity), LinearLayout.LayoutParams(-1, (1400 * density).toInt()))
        scroll.addView(column)
        activity.setContentView(scroll)
    }
    fun localPoint(x: Float, y: Float): PointF {
        val side = min(view.width, view.height).toFloat()
        return PointF((view.width - side) / 2 + side * x / 1000f, (view.height - side) / 2 + side * y / 1000f)
    }
    fun screenPoint(local: PointF): PointF {
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        return PointF(origin[0] + local.x, origin[1] + local.y)
    }
    fun detach(activity: Activity) { activity.setContentView(FrameLayout(activity)); context.clean() }
}

/** Real touchscreen input goes through the window, ScrollView and actual LongPressFrame. */
internal class TouchGesture(private val start: PointF) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val down = SystemClock.uptimeMillis()
    fun send(action: Int, point: PointF = start) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
    fun secondPointer(action: Int, second: PointF) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(start, second).map { point -> MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
