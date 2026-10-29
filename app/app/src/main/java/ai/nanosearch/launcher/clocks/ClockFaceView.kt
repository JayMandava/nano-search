package ai.nanosearch.launcher.clocks

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import java.text.DateFormat
import java.util.Date

/** Native Nano clock view: the wallpaper uses the same renderer factory and motion policy. */
open class ClockFaceView @JvmOverloads constructor(context: Context, faceId: String = ClockFaces.STEEL_ID) : View(context) {
    var faceId: String = ClockFaces.normalizeSelection(faceId)
        set(value) {
            val normalized = ClockFaces.normalizeSelection(value)
            if (field == normalized) { refreshRenderer(); return }
            field = normalized
            refreshRenderer()
            lastDescription = Long.MIN_VALUE
            invalidate()
        }
    protected var renderer = ClockFaces.renderer(context, this.faceId)
        private set
    private var rendererIdentity = ClockFaceRegistry.identity(context, this.faceId)
    private var registrySubscription: AutoCloseable? = null
    private val battery = PhoneBatteryObserver(context) { percent ->
        renderer.onBatteryChanged(percent); lastDescription = Long.MIN_VALUE; invalidate()
    }
    private fun updateBatteryObservation() {
        if (isAttachedToWindow && aggregateVisible && screenActive && windowVisibility == VISIBLE && faceId == ClockFaces.CARBON_ID) battery.start()
        else battery.stop()
    }
    private val bounds = RectF()
    private val visibleRect = Rect()
    private var aggregateVisible = false
    private var screenActive = true
    private var receiving = false
    private var formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
    private var lastDescription = Long.MIN_VALUE
    val motionPolicy = ClockMotionPolicy(context) { cancelFrame(); invalidate() }
    var isFrameScheduled: Boolean = false
        private set
    val isMotionEnabled: Boolean get() = motionPolicy.smooth
    val isRenderingVisible: Boolean
        get() = isAttachedToWindow && aggregateVisible && screenActive && windowVisibility == VISIBLE && isShown && getGlobalVisibleRect(visibleRect)
    private val tick = Runnable {
        isFrameScheduled = false
        if (isRenderingVisible) invalidate()
    }
    private val scrollChanged = ViewTreeObserver.OnScrollChangedListener {
        if (isRenderingVisible) invalidate() else cancelFrame()
    }
    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenActive = false; onScreenInteractiveChanged(false) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { screenActive = true; onScreenInteractiveChanged(true) }
            }
            updateBatteryObservation()
            renderer.onTimeChanged()
            formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            lastDescription = Long.MIN_VALUE
            motionPolicy.refresh()
            cancelFrame()
            if (screenActive) invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            val options = ActivityOptions.makeScaleUpAnimation(this, width / 2, height / 2, 1, 1).toBundle()
            ClockFaceClockIntent.launch(context, options)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registrySubscription = ClockFaceRegistry.observe(context) {
            refreshRenderer(); lastDescription = Long.MIN_VALUE; invalidate()
        }
        refreshRenderer()
        if (!receiving) {
            context.registerReceiver(clockChanged, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }, Context.RECEIVER_NOT_EXPORTED)
            receiving = true
        }
        screenActive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        updateBatteryObservation()
        renderer.onTimeChanged()
        motionPolicy.attach()
        viewTreeObserver.addOnScrollChangedListener(scrollChanged)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        battery.stop()
        registrySubscription?.close(); registrySubscription = null
        cancelFrame()
        viewTreeObserver.removeOnScrollChangedListener(scrollChanged)
        motionPolicy.detach()
        if (receiving) { context.unregisterReceiver(clockChanged); receiving = false }
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregateVisible = isVisible
        updateBatteryObservation()
        cancelFrame()
        if (isVisible && isAttachedToWindow) { renderer.onTimeChanged(); motionPolicy.refresh(); invalidate() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateBatteryObservation()
        cancelFrame()
        if (visibility == VISIBLE && isAttachedToWindow) { renderer.onTimeChanged(); motionPolicy.refresh(); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { bounds.set(0f, 0f, w.toFloat(), h.toFloat()) }

    override fun onDraw(canvas: Canvas) {
        refreshRenderer()
        val now = System.currentTimeMillis()
        drawFace(canvas, bounds, now, motionPolicy.smooth)
        val wholeSecond = Math.floorDiv(now, 1000L)
        if (lastDescription != wholeSecond) {
            contentDescription = accessibilityDescription(now)
            lastDescription = wholeSecond
        }
        cancelFrame()
        if (isRenderingVisible) {
            isFrameScheduled = true
            if (motionPolicy.smooth) postOnAnimation(tick)
            else postDelayed(tick, motionPolicy.frameDelayMillis(now))
        }
    }

    protected open fun drawFace(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
        renderer.draw(canvas, bounds, epochMillis, motion)
    }

    protected open fun onScreenInteractiveChanged(interactive: Boolean) = Unit

    protected open fun accessibilityDescription(epochMillis: Long): String =
        "${ClockFaces.labelFor(faceId)}, ${formatter.format(Date(epochMillis))}" + if (faceId == ClockFaces.CARBON_ID) ". ${batteryDescription()}" else ""

    protected fun batteryDescription(): String = battery.percent?.let { "Phone battery $it percent, ${PhoneBattery.band(it)?.name?.lowercase()}." }
        ?: "Phone battery unavailable."

    private fun refreshRenderer() {
        val identity = ClockFaceRegistry.identity(context, faceId)
        if (identity == rendererIdentity) return
        renderer.release()
        renderer = ClockFaces.renderer(context, faceId)
        rendererIdentity = identity
        updateBatteryObservation()
        if (faceId == ClockFaces.CARBON_ID) renderer.onBatteryChanged(battery.percent)
        lastDescription = Long.MIN_VALUE
        invalidate()
    }

    private fun cancelFrame() { removeCallbacks(tick); isFrameScheduled = false }
}

class CarbonDialView(context: Context) : ClockFaceView(context, ClockFaces.CARBON_ID)
