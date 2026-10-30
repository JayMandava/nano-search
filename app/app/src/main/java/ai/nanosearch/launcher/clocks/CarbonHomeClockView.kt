package ai.nanosearch.launcher.clocks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import java.util.Locale
import kotlin.math.hypot

/** Stateful only in the keyed home factory; wallpaper has its own rev-only tap handler; generic previews remain silent. */
class CarbonHomeClockView @JvmOverloads constructor(
    context: Context,
    val instanceKey: String = UNPLACED_KEY_PREFIX + java.util.UUID.randomUUID(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : ClockFaceView(context, ClockFaces.CARBON_ID) {
    private var lifecycleReady = false
    private var pendingClickTarget: CarbonPusherAction? = null
    private val store = if (instanceKey.startsWith(UNPLACED_KEY_PREFIX)) null else CarbonChronographStore(context, instanceKey)
    private val bootCount = if (store == null) -1 else Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    var chronographState = store?.load(elapsedRealtime(), bootCount) ?: CarbonChronographState()
        private set
    var targetLayout = CarbonPusherGeometry.layout(0f, 0f, resources.displayMetrics.density)
        private set
    private var nextCheckpointAt = elapsedRealtime() + CHECKPOINT_INTERVAL
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var gestureOwned = false
    private var gestureValid = false
    private var downAction: CarbonPusherAction? = null
    private var downPointer = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var accessibilityFocus = NO_VIRTUAL_ID
    private var keyboardFocus = NO_VIRTUAL_ID
    private var hovered = NO_VIRTUAL_ID
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xb3c5e8dc.toInt(); strokeWidth = resources.displayMetrics.density * 2f }
    private val provider = ControlNodeProvider()
    private var revSound: CarbonRevSound? = null
    private var lumeState = CarbonLumeState()
    var isLumeCallbackScheduled = false
        private set
    val lumeActive: Boolean get() = isRenderingVisible && lumeState.active(elapsedRealtime())
    val lumeAlpha: Float get() = if (isRenderingVisible) lumeState.alpha(elapsedRealtime(), !motionPolicy.smooth) else 0f
    private val lumeOff = Runnable {
        isLumeCallbackScheduled = false
        lumeState = lumeState.clear()
        if (isRenderingVisible) { invalidate(); sendVirtualEvent(LUME_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
    }

    init {
        setOnClickListener(null)
        isClickable = true
        isFocusable = true
        contentDescription = accessibilityDescription(System.currentTimeMillis())
        lifecycleReady = true
    }

    fun elapsedChronographMillis(): Long = chronographState.elapsed(elapsedRealtime())
    fun displayedChronographMillis(): Long = chronographState.displayElapsed(elapsedRealtime())

    /** Shared safe command seam. A disabled pusher never becomes an Open Clock fallback. */
    fun performPusherAction(action: CarbonPusherAction): Boolean {
        if (!chronographState.enabled(action)) return false
        if (action == CarbonPusherAction.OPEN_CLOCK) return ClockFaceClockIntent.launch(context)
        if (action == CarbonPusherAction.REV) {
            if (!isRenderingVisible) return false
            val sound=revSound ?: CarbonRevSound({AndroidRevAudioBackend(context)}).also {revSound=it}
            sound.setActive(true)
            return sound.play()
        }
        if (action == CarbonPusherAction.LUME) {
            if (!isRenderingVisible) return false
            lumeState = lumeState.trigger(elapsedRealtime())
            removeCallbacks(lumeOff)
            isLumeCallbackScheduled = postDelayed(lumeOff, CarbonLumeState.DURATION_MILLIS)
            invalidate()
            sendVirtualEvent(LUME_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            return true
        }
        val now = elapsedRealtime()
        chronographState = chronographState.transition(action, now)
        checkpoint(now)
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
        sendVirtualEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        return true
    }

    override fun drawFace(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
        val now = elapsedRealtime()
        val face = renderer as? CarbonClockFaceRenderer
        if (face != null) face.drawChronograph(canvas, bounds, epochMillis, motion,
            chronographState.displayElapsed(now), chronographState.running, lumeAlpha)
        else super.drawFace(canvas, bounds, epochMillis, motion)
        actionFor(keyboardFocus)?.let { canvas.drawRoundRect(targetLayout.boundsFor(it), 5f, 5f, focusPaint) }
        if (chronographState.running && now >= nextCheckpointAt) checkpoint(now)
    }

    // Stable between commands: elapsed/status belongs to the virtual nodes, not frame announcements.
    override fun accessibilityDescription(epochMillis: Long): String = "Carbon 01 chronograph. ${batteryDescription()} Explore controls."

    override fun onAttachedToWindow() {
        store?.let { chronographState = it.load(elapsedRealtime(), bootCount) }
        nextCheckpointAt = elapsedRealtime() + CHECKPOINT_INTERVAL
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        checkpoint(elapsedRealtime()); cancelGesture(); clearLume(); revSound?.setActive(false)
        clearVirtualFocus()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        if (lifecycleReady && !isVisible) { cancelGesture(); clearLume(); revSound?.setActive(false); clearVirtualFocus(); checkpoint(elapsedRealtime()) }
        super.onVisibilityAggregated(isVisible)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (lifecycleReady && visibility != VISIBLE) { cancelGesture(); clearLume(); revSound?.setActive(false); clearVirtualFocus(); checkpoint(elapsedRealtime()) }
        super.onWindowVisibilityChanged(visibility)
    }

    override fun onScreenInteractiveChanged(interactive: Boolean) {
        if (lifecycleReady && !interactive) { cancelGesture(); clearLume(); revSound?.setActive(false); clearVirtualFocus(); checkpoint(elapsedRealtime()) }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (lifecycleReady && !gainFocus) { keyboardFocus = NO_VIRTUAL_ID; invalidate() }
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (lifecycleReady) {
            cancelGesture(); clearLume(); revSound?.setActive(false)
            targetLayout = CarbonPusherGeometry.layout(w.toFloat(), h.toFloat(), resources.displayMetrics.density)
            hovered = NO_VIRTUAL_ID
        }
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                downAction = targetLayout.hitTest(event.x, event.y)
                gestureOwned = downAction != null
                gestureValid = gestureOwned && event.pointerCount == 1
                downPointer = event.getPointerId(0)
                downX = event.x; downY = event.y; downTime = event.eventTime
                return gestureOwned
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { gestureValid = false; return gestureOwned }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(downPointer)
                if (event.pointerCount != 1 || index < 0 || hypot(event.getX(index) - downX, event.getY(index) - downY) > slop) gestureValid = false
                return gestureOwned
            }
            MotionEvent.ACTION_UP -> {
                val owned = gestureOwned
                val action = downAction
                val withinTime = event.eventTime >= downTime && event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                val matching = event.pointerCount == 1 && event.getPointerId(0) == downPointer && targetLayout.hitTest(event.x, event.y) == action
                val withinSlop = hypot(event.x - downX, event.y - downY) <= slop
                val invoke = gestureValid && matching && withinSlop && withinTime && action != null
                cancelGesture()
                if (invoke) {
                    pendingClickTarget = requireNotNull(action)
                    try { performClick() } finally { pendingClickTarget = null }
                }
                return owned
            }
            MotionEvent.ACTION_CANCEL -> { val owned = gestureOwned; cancelGesture(); return owned }
        }
        return gestureOwned
    }

    // Host-wide clicks must not dispatch Clock: only the actual dial or its virtual action can.
    override fun performClick(): Boolean {
        val target = pendingClickTarget ?: return false
        pendingClickTarget = null
        super.performClick()
        return performPusherAction(target)
    }

    private fun cancelGesture() { gestureOwned = false; gestureValid = false; downAction = null; downPointer = -1; pendingClickTarget = null }
    // Hidden nodes cannot retain focus, and hiding a subtree is not a spoken status update.
    private fun clearVirtualFocus() { accessibilityFocus = NO_VIRTUAL_ID; keyboardFocus = NO_VIRTUAL_ID; hovered = NO_VIRTUAL_ID }
    private fun clearLume() { removeCallbacks(lumeOff); isLumeCallbackScheduled = false; lumeState = lumeState.clear() }
    private fun checkpoint(now: Long) { store?.save(chronographState, now, bootCount); nextCheckpointAt = now + CHECKPOINT_INTERVAL }
    private fun label(action: CarbonPusherAction): String = when (action) {
        CarbonPusherAction.START_PAUSE -> if (chronographState.running) "Pause chronograph" else if (chronographState.accumulatedMillis > 0L) "Resume chronograph" else "Start chronograph"
        CarbonPusherAction.SPLIT_RELEASE -> if (chronographState.splitMillis == null) "Hold split" else "Release split"
        CarbonPusherAction.RESET -> "Reset chronograph"
        CarbonPusherAction.OPEN_CLOCK -> "Open system Clock"
        CarbonPusherAction.LUME -> "Illuminate hands and markers"
        CarbonPusherAction.REV -> "Play race-car sound"
    }

    private fun queriedTimeLabel(): String = super.accessibilityDescription(System.currentTimeMillis())

    private fun status(): String {
        val mode = if (chronographState.running) "Running" else "Paused"
        val elapsed = duration(chronographState.elapsed(elapsedRealtime()))
        return if (chronographState.splitMillis != null) "$mode. Elapsed $elapsed. Split held at ${duration(requireNotNull(chronographState.splitMillis))}."
        else "$mode. Elapsed $elapsed."
    }

    private fun duration(value: Long): String {
        val ms = value.coerceAtLeast(0L)
        return String.format(Locale.getDefault(), "%02d:%02d:%02d.%d", ms / 3_600_000L, ms / 60_000L % 60L, ms / 1000L % 60L, ms / 100L % 10L)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider
    private fun virtualId(action: CarbonPusherAction?): Int = action?.let { it.ordinal + 1 } ?: NO_VIRTUAL_ID
    private fun actionFor(id: Int): CarbonPusherAction? = CarbonPusherAction.entries.getOrNull(id - 1)

    private inner class ControlNodeProvider : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) return AccessibilityNodeInfo.obtain(this@CarbonHomeClockView).also { node ->
                this@CarbonHomeClockView.onInitializeAccessibilityNodeInfo(node)
                node.isClickable = false
                node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                node.contentDescription = "${queriedTimeLabel()}. Chronograph. ${status()}"
                CarbonPusherAction.entries.forEach { node.addChild(this@CarbonHomeClockView, virtualId(it)) }
            }
            val action = actionFor(virtualViewId) ?: return null
            val parentBounds = Rect().also { targetLayout.boundsFor(action).roundOut(it) }
            val localVisible = Rect()
            val visible = isRenderingVisible && getLocalVisibleRect(localVisible) && Rect.intersects(parentBounds, localVisible)
            return AccessibilityNodeInfo.obtain().also { node ->
                node.setSource(this@CarbonHomeClockView, virtualViewId)
                node.setParent(this@CarbonHomeClockView)
                node.packageName = context.packageName
                node.className = "android.widget.Button"
                node.text = label(action)
                node.contentDescription = if (action == CarbonPusherAction.OPEN_CLOCK) "${label(action)}. ${queriedTimeLabel()}" else "${label(action)}. ${status()}"
                node.stateDescription = when (action) {
                    CarbonPusherAction.LUME -> if (lumeActive) "Lume on" else "Lume off"
                    CarbonPusherAction.OPEN_CLOCK -> queriedTimeLabel()
                    else -> status()
                }
                node.isEnabled = chronographState.enabled(action)
                node.isClickable = node.isEnabled
                node.isFocusable = true
                node.isFocused = keyboardFocus == virtualViewId
                node.isAccessibilityFocused = accessibilityFocus == virtualViewId
                node.isVisibleToUser = visible
                node.setBoundsInParent(parentBounds)
                val screenBounds = Rect(parentBounds)
                val location = IntArray(2); getLocationOnScreen(location)
                screenBounds.offset(location[0], location[1]); node.setBoundsInScreen(screenBounds)
                node.addAction(if (node.isFocused) AccessibilityNodeInfo.ACTION_CLEAR_FOCUS else AccessibilityNodeInfo.ACTION_FOCUS)
                node.addAction(if (node.isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                if (node.isEnabled) node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? = createAccessibilityNodeInfo(
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) accessibilityFocus else keyboardFocus)

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val command = actionFor(virtualViewId) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> if (isRenderingVisible) performPusherAction(command) else false
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> if (accessibilityFocus == virtualViewId) false else {
                    val old = accessibilityFocus; accessibilityFocus = virtualViewId
                    if (old != NO_VIRTUAL_ID) sendVirtualEvent(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); invalidate(); true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> if (accessibilityFocus != virtualViewId) false else {
                    accessibilityFocus = NO_VIRTUAL_ID; sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); invalidate(); true
                }
                AccessibilityNodeInfo.ACTION_FOCUS -> { requestFocus(); keyboardFocus = virtualViewId; sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_FOCUSED); invalidate(); true }
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> if (keyboardFocus != virtualViewId) false else { keyboardFocus = NO_VIRTUAL_ID; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (accessibility?.isTouchExplorationEnabled != true) return super.onHoverEvent(event)
        val next = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) NO_VIRTUAL_ID else virtualId(targetLayout.hitTest(event.x, event.y))
        if (next != hovered) {
            if (next != NO_VIRTUAL_ID) sendVirtualEvent(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered != NO_VIRTUAL_ID) sendVirtualEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = next
        }
        return next != NO_VIRTUAL_ID
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> {
                val direction = if (event.isShiftPressed) -1 else 1
                val next = if (keyboardFocus == NO_VIRTUAL_ID) 1 else keyboardFocus + direction
                if (event.keyCode == KeyEvent.KEYCODE_TAB && next !in 1..CarbonPusherAction.entries.size) return super.dispatchKeyEvent(event)
                keyboardFocus = Math.floorMod(next - 1, CarbonPusherAction.entries.size) + 1
                sendVirtualEvent(keyboardFocus, AccessibilityEvent.TYPE_VIEW_FOCUSED); invalidate(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                keyboardFocus = if (keyboardFocus == NO_VIRTUAL_ID) CarbonPusherAction.entries.size else Math.floorMod(keyboardFocus - 2, CarbonPusherAction.entries.size) + 1
                sendVirtualEvent(keyboardFocus, AccessibilityEvent.TYPE_VIEW_FOCUSED); invalidate(); return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                actionFor(keyboardFocus)?.let { if (event.repeatCount == 0) performPusherAction(it); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendVirtualEvent(id: Int, type: Int) {
        if (accessibility?.isEnabled != true || !isAttachedToWindow) return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = if (id == View.NO_ID) javaClass.name else "android.widget.Button"
        event.setSource(this, id)
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
        actionFor(id)?.let { event.contentDescription = "${label(it)}. ${status()}" }
        parent?.requestSendAccessibilityEvent(this, event)
    }

    companion object {
        const val START_PAUSE_ID = 1
        const val SPLIT_RELEASE_ID = 2
        const val RESET_ID = 3
        const val OPEN_CLOCK_ID = 4
        const val LUME_ID = 5
        private const val UNPLACED_KEY_PREFIX = "unplaced-carbon:"
        private const val NO_VIRTUAL_ID = Int.MIN_VALUE
        private const val CHECKPOINT_INTERVAL = 30_000L
    }
}
