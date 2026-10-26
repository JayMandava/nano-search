package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LongPressFrame
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
    var completedDraws = 0
        private set
    var onTouchDispatched: ((TouchDispatchReceipt) -> Unit)? = null
    private var childEventTime = Long.MIN_VALUE
    private var childEventAction = -1
    val scroll = object : ScrollView(activity) {
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // Unlike isFrameScheduled, this evidence remains true between animation ticks.
            // Count only after the real clock's draw traversal has returned.
            completedDraws++
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val deliveredAt = SystemClock.uptimeMillis()
            val handled = super.dispatchTouchEvent(event)
            val receipt = TouchDispatchReceipt(event.actionMasked, event.downTime, event.eventTime,
                deliveredAt, handled, childEventTime == event.eventTime && childEventAction == event.actionMasked)
            val state = describe()
            onTouchDispatched?.invoke(receipt)
            Log.i("CarbonPusherTest", "touch=$receipt $state")
            return handled
        }
    }
    val frame = LongPressFrame(activity)
    var longPresses = 0
        private set
    init {
        val density = activity.resources.displayMetrics.density
        // Observe delivery without consuming, replacing, or directly invoking the clock's handler.
        view.setOnTouchListener { _, event ->
            childEventTime = event.eventTime
            childEventAction = event.actionMasked
            false
        }
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
    fun describe(): String {
        val visible = Rect().also { view.getGlobalVisibleRect(it) }
        val home = view as? CarbonHomeClockView
        return "uptime=${SystemClock.uptimeMillis()} focus=${view.hasWindowFocus()} visible=${view.isRenderingVisible} " +
            "window=${view.windowVisibility} rect=$visible draws=$completedDraws frame=${view.isFrameScheduled} " +
            "running=${home?.chronographState?.running} elapsed=${home?.elapsedChronographMillis()} " +
            "lume=${home?.lumeActive} expiry=${home?.isLumeCallbackScheduled} longPresses=$longPresses"
    }
    fun detach(activity: Activity) {
        onTouchDispatched = null
        view.setOnTouchListener(null)
        activity.setContentView(FrameLayout(activity))
        context.clean()
    }
}

internal data class TouchDispatchReceipt(val action: Int, val downTime: Long, val eventTime: Long,
    val deliveredAt: Long, val handled: Boolean, val reachedClock: Boolean)

/** Real touchscreen input goes through the window, ScrollView and actual LongPressFrame. */
internal class TouchGesture(private val start: PointF) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val downTime = SystemClock.uptimeMillis()
    fun tap() {
        // Android's input command injects a complete short tap with one event-time origin.
        // Unlike sendPointerSync, it does not age DOWN while synchronizing surfaces or wait
        // for another surface transaction after UP. Both events still traverse the real window.
        require(start.x.isFinite() && start.y.isFinite())
        val command = "input touchscreen tap ${start.x} ${start.y}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            val output = it.readBytes().toString(Charsets.UTF_8).trim()
            check(output.isEmpty()) { "Touch input command reported: $output" }
        }
    }
    fun send(action: Int, point: PointF = start) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
    fun secondPointer(action: Int, second: PointF) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(start, second).map { point -> MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
