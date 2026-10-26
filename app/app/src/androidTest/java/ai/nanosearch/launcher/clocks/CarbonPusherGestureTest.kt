package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Display
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CarbonPusherGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var elapsed = 1000L
    @get:Rule val timing = object : TestWatcher() {
        override fun starting(description: Description) { Log.i("CarbonPusherTest", "START ${description.methodName} elapsedRealtime=${SystemClock.elapsedRealtime()}") }
        override fun finished(description: Description) { Log.i("CarbonPusherTest", "END ${description.methodName} elapsedRealtime=${SystemClock.elapsedRealtime()}") }
    }
    private fun home(scene: CarbonPusherScene) = scene.view as CarbonHomeClockView
    private fun point(scene: CarbonPusherScene, action: CarbonPusherAction): PointF {
        lateinit var result: PointF
        instrumentation.runOnMainSync {
            val bounds = home(scene).targetLayout.boundsFor(action)
            result = scene.screenPoint(PointF(bounds.centerX(), bounds.centerY()))
        }
        return result
    }
    private fun tap(scene: CarbonPusherScene, action: CarbonPusherAction, afterDispatch: (TouchDispatchReceipt) -> Unit = {}) {
        val gesture = TouchGesture(point(scene, action))
        val received = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            var down: TouchDispatchReceipt? = null
            scene.onTouchDispatched = { receipt ->
                if (receipt.action == MotionEvent.ACTION_DOWN) down = receipt
                if (receipt.action == MotionEvent.ACTION_UP) {
                    try {
                        val first = requireNotNull(down) { "Actual $action UP arrived without DOWN" }
                        assertTrue("Real $action DOWN must reach the clock: $first", first.handled && first.reachedClock)
                        assertEquals("DOWN and UP must be the same actual gesture", first.downTime, receipt.downTime)
                        assertTrue("The input command must produce a short tap, not a long press", receipt.eventTime - first.eventTime in 0 until ViewConfiguration.getLongPressTimeout().toLong())
                        assertTrue("Real $action UP must be handled by the fixture hierarchy: $receipt", receipt.handled)
                        assertTrue("Real $action UP must reach the clock, not only its parent: $receipt", receipt.reachedClock)
                        afterDispatch(receipt)
                    } catch (error: Throwable) { failure.set(error) }
                    finally { received.countDown() }
                }
            }
        }
        val start = SystemClock.uptimeMillis()
        try {
            gesture.tap()
            // Observe transient state in its actual dispatch receipt, not after shell transport
            // or an unrelated rendering/main-queue barrier has consumed the effect's lifetime.
            assertTrue("The actual window must deliver $action UP", received.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync {
                scene.onTouchDispatched = null
                Log.i("CarbonPusherTest", "tap=$action transportMs=${SystemClock.uptimeMillis() - start} ${scene.describe()}")
            }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var reached = false
        while (!reached && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { reached = condition() }
            if (!reached) SystemClock.sleep(20)
        }
        assertTrue(message, reached)
    }
    private fun scene(block: (ClockActivitySession<ClockTestHostActivity>, CarbonPusherScene) -> Unit) {
        elapsed = 1000L
        // Connect before any transient gesture begins; shell setup is not part of touch duration.
        instrumentation.uiAutomation
        ClockActivitySession.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var scene: CarbonPusherScene
            scenario.onActivity { activity -> scene = CarbonPusherScene(activity, { CarbonHomeClockView(it, "home-test-instance", elapsedRealtime = { elapsed }) }) }
            try {
                awaitReady(scene)
                block(scenario, scene)
            } finally { scenario.onActivity { scene.detach(it) } }
        }
    }
    private fun awaitReady(scene: CarbonPusherScene, afterDraw: Int = 0) {
        try {
            await("Real gesture fixture must have input focus and a completed draw traversal") {
                scene.view.hasWindowFocus() && scene.view.isRenderingVisible && scene.completedDraws > afterDraw && scene.view.width > 0 && scene.view.height > 0
            }
        } finally { instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "readiness ${scene.describe()}") } }
    }

    @Test fun actualButtonTapsTogglePauseSplitAndResetWithoutOpeningClock() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.SPLIT_RELEASE)
        instrumentation.runOnMainSync { assertFalse(home(scene).chronographState.running); assertNull(home(scene).chronographState.splitMillis) }
        tap(scene, CarbonPusherAction.START_PAUSE)
        instrumentation.runOnMainSync { assertTrue(home(scene).chronographState.running); elapsed = 2000 }
        tap(scene, CarbonPusherAction.RESET)
        instrumentation.runOnMainSync { assertTrue(home(scene).chronographState.running); assertEquals(1000L, home(scene).chronographState.elapsed(elapsed)) }
        tap(scene, CarbonPusherAction.SPLIT_RELEASE)
        instrumentation.runOnMainSync { assertEquals(1000L, home(scene).chronographState.displayElapsed(elapsed)); elapsed = 3000 }
        tap(scene, CarbonPusherAction.SPLIT_RELEASE)
        instrumentation.runOnMainSync { assertNull(home(scene).chronographState.splitMillis); assertEquals(2000L, home(scene).chronographState.displayElapsed(elapsed)) }
        tap(scene, CarbonPusherAction.START_PAUSE)
        instrumentation.runOnMainSync { assertFalse(home(scene).chronographState.running) }
        tap(scene, CarbonPusherAction.RESET)
        instrumentation.runOnMainSync { assertEquals(0L, home(scene).chronographState.elapsed(elapsed)); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun actualDialTapOpensClockOnceAndDoesNotChangeChronograph() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.OPEN_CLOCK) { assertEquals(1, scene.context.clockLaunches.size); assertEquals(CarbonChronographState(), home(scene).chronographState) }
    }

    @Test fun draggingOutAndReturningToTheButtonNeverCommitsAnAction() = scene { scenario, scene ->
        val start = point(scene, CarbonPusherAction.START_PAUSE)
        val distance = 40 * scene.view.resources.displayMetrics.density
        TouchGesture(start).apply {
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_MOVE, PointF(start.x - distance, start.y))
            send(MotionEvent.ACTION_MOVE, start)
            send(MotionEvent.ACTION_UP)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(home(scene).chronographState.running); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun cancelledAndMultiPointerGesturesNeverCommitOrOpenClock() = scene { scenario, scene ->
        val start = point(scene, CarbonPusherAction.START_PAUSE)
        TouchGesture(start).apply { send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_CANCEL) }
        TouchGesture(start).apply {
            send(MotionEvent.ACTION_DOWN)
            secondPointer(MotionEvent.ACTION_POINTER_DOWN, PointF(start.x - 40, start.y))
            secondPointer(MotionEvent.ACTION_POINTER_UP, PointF(start.x - 40, start.y))
            send(MotionEvent.ACTION_UP)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(home(scene).chronographState.running); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun swipeFromButtonOrDialStillScrollsTheActualParent() {
        for (action in listOf(CarbonPusherAction.START_PAUSE, CarbonPusherAction.OPEN_CLOCK)) scene { scenario, scene ->
            val start = point(scene, action)
            val density = scene.view.resources.displayMetrics.density
            TouchGesture(start).apply {
                send(MotionEvent.ACTION_DOWN)
                for (step in 1..8) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, PointF(start.x, start.y - 12 * step * density)) }
                send(MotionEvent.ACTION_UP, PointF(start.x, start.y - 96 * density))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Swipe starting at$action must scroll the real ScrollView", scene.scroll.scrollY > 0)
                assertFalse(home(scene).chronographState.running)
                assertTrue(scene.context.clockLaunches.isEmpty())
            }
        }
    }

    @Test fun longPressOnButtonOrDialBelongsToTheActualLongPressFrame() {
        for (action in listOf(CarbonPusherAction.START_PAUSE, CarbonPusherAction.OPEN_CLOCK)) scene { scenario, scene ->
            TouchGesture(point(scene, action)).apply {
                send(MotionEvent.ACTION_DOWN)
                try { await("The actual LongPressFrame must fire while the finger is still down") { scene.longPresses == 1 } }
                finally { send(MotionEvent.ACTION_UP) }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(1, scene.longPresses); assertFalse(home(scene).chronographState.running); assertTrue(scene.context.clockLaunches.isEmpty()) }
        }
    }

    @Test fun resizingDuringTheGestureInvalidatesItsOldHitTarget() = scene { scenario, scene ->
        val gesture = TouchGesture(point(scene, CarbonPusherAction.START_PAUSE))
        gesture.send(MotionEvent.ACTION_DOWN)
        instrumentation.runOnMainSync { scene.frame.layoutParams = (scene.frame.layoutParams as LinearLayout.LayoutParams).apply { height -= 1 } }
        instrumentation.waitForIdleSync()
        gesture.send(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(home(scene).chronographState.running); assertTrue(scene.context.clockLaunches.isEmpty()) }
        tap(scene, CarbonPusherAction.START_PAUSE)
        instrumentation.runOnMainSync { assertTrue("Fresh taps must use the resized geometry", home(scene).chronographState.running) }
    }

    @Test fun virtualAccessibilityControlsExposeEnabledStateAndTheSameActions() = scene { scenario, scene ->
        instrumentation.runOnMainSync {
            val provider = requireNotNull(home(scene).accessibilityNodeProvider)
            fun node(id: Int) = requireNotNull(provider.createAccessibilityNodeInfo(id))
            assertTrue(node(1).isEnabled)
            assertFalse(node(2).isEnabled)
            assertTrue(node(3).isEnabled)
            assertFalse(provider.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(home(scene).chronographState.running)
            assertTrue(node(2).isEnabled)
            assertFalse(node(3).isEnabled)
            assertFalse(provider.performAction(3, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertNotNull(home(scene).chronographState.splitMillis)
            assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertNull(home(scene).chronographState.splitMillis)
            assertTrue(provider.performAction(3, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(0L, home(scene).chronographState.elapsed(elapsed))
            val crown = node(5)
            assertTrue(crown.isEnabled); assertTrue(crown.contentDescription?.isNotBlank() == true)
            assertTrue(provider.performAction(5, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(home(scene).lumeActive)
            assertEquals(CarbonChronographState(), home(scene).chronographState)
            assertTrue(scene.context.clockLaunches.isEmpty())
            assertTrue(provider.performAction(4, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, scene.context.clockLaunches.size)
        }
    }

    @Test fun hiddenLifecycleStopsDrawingWhileElapsedChronographTimeContinues() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.START_PAUSE) { assertTrue("Start must commit before stopping the host", home(scene).chronographState.running) }
        instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "before CREATED ${scene.describe()}") }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("The actually stopped host must become non-rendering and cancel its frame callback") { !home(scene).isRenderingVisible && !home(scene).isFrameScheduled }
        var drawsBeforeResume = 0
        instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "after CREATED ${scene.describe()}"); assertFalse(home(scene).isFrameScheduled); drawsBeforeResume = scene.completedDraws; elapsed = 61000 }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitReady(scene, drawsBeforeResume)
        instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "after RESUMED ${scene.describe()}"); assertTrue(home(scene).chronographState.running); assertEquals(60000L, home(scene).chronographState.elapsed(elapsed)) }
        tap(scene, CarbonPusherAction.START_PAUSE)
        instrumentation.runOnMainSync { assertEquals(60000L, home(scene).chronographState.elapsed(elapsed)); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun crownConsumesItsOwnTapAndRepeatsWithoutChangingChronographOrPreferences() = scene { scenario, scene ->
        lateinit var before: CarbonChronographState
        lateinit var preferences: Map<String, *>
        tap(scene, CarbonPusherAction.START_PAUSE) {
            assertTrue("Start must commit before testing the crown", home(scene).chronographState.running)
            elapsed = 2000
            before = home(scene).chronographState
            preferences = scene.context.getSharedPreferences(CarbonChronographStore.PREFS_FILE, android.content.Context.MODE_PRIVATE).all.toMap()
        }
        tap(scene, CarbonPusherAction.LUME) {
            assertTrue(home(scene).lumeActive); assertEquals(1f, home(scene).lumeAlpha, 0f)
            assertEquals(before, home(scene).chronographState)
            assertEquals(preferences, scene.context.getSharedPreferences(CarbonChronographStore.PREFS_FILE, android.content.Context.MODE_PRIVATE).all)
            elapsed = 3500
            home(scene).invalidate()
            assertTrue(home(scene).lumeAlpha in 0.001f..0.999f)
        }
        tap(scene, CarbonPusherAction.LUME) {
            assertEquals(1f, home(scene).lumeAlpha, 0f)
            assertEquals(before, home(scene).chronographState)
            assertEquals(2500L, home(scene).elapsedChronographMillis())
            elapsed = 6500
            home(scene).invalidate()
            assertFalse(home(scene).lumeActive); assertEquals(0f, home(scene).lumeAlpha, 0f); assertTrue(scene.context.clockLaunches.isEmpty())
        }
    }

    @Test fun reducedMotionCrownIsStaticUntilExpiryThenOff() = scene { scenario, scene ->
        instrumentation.runOnMainSync { LockClock.setReducedMotion(scene.context, true) }
        await("Reduced-motion policy must be applied before the crown test") { !home(scene).isMotionEnabled }
        tap(scene, CarbonPusherAction.LUME) {
            assertEquals(1f, home(scene).lumeAlpha, 0f); elapsed = 3999; home(scene).invalidate()
            assertEquals(1f, home(scene).lumeAlpha, 0f); elapsed = 4000; home(scene).invalidate()
            assertEquals(0f, home(scene).lumeAlpha, 0f); assertFalse(home(scene).lumeActive); assertTrue(scene.context.clockLaunches.isEmpty())
        }
    }

    @Test fun hidingOrStoppingTheHostCancelsItsTransientGlow() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.LUME) { assertTrue(home(scene).lumeActive); home(scene).visibility = View.GONE }
        await("A hidden home face must cancel its glow and callbacks") { !home(scene).lumeActive && !home(scene).isLumeCallbackScheduled && !home(scene).isFrameScheduled }
        var drawsBeforeShowing = 0
        instrumentation.runOnMainSync { drawsBeforeShowing = scene.completedDraws; home(scene).visibility = View.VISIBLE }
        awaitReady(scene, drawsBeforeShowing)
        tap(scene, CarbonPusherAction.LUME) { assertTrue(home(scene).lumeActive); assertTrue(home(scene).isLumeCallbackScheduled) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("The actually stopped host must hide and cancel its glow, expiry and frame callbacks") { !home(scene).isRenderingVisible && !home(scene).lumeActive && !home(scene).isLumeCallbackScheduled && !home(scene).isFrameScheduled }
        instrumentation.runOnMainSync { assertFalse(home(scene).lumeActive); assertFalse(home(scene).isLumeCallbackScheduled); assertFalse(home(scene).isFrameScheduled) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.runOnMainSync {}
        instrumentation.runOnMainSync { assertFalse(home(scene).lumeActive); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun screenOffCancelsGlowOnTheDedicatedOptInEmulator() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("testScreenOff") == "true")
        assertTrue("Screen-off input is forbidden on physical devices", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        // Establish automation before the transient effect starts; connection setup is not part
        // of the three-second screen-off assertion window.
        val automation = instrumentation.uiAutomation
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        scene { scenario, scene ->
            val power = requireNotNull(scene.context.getSystemService(PowerManager::class.java))
            val displays = requireNotNull(scene.context.getSystemService(DisplayManager::class.java))
            val display = requireNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
            val glowDispatchedAt = AtomicLong()
            val screenOffReceivedAt = AtomicLong()
            val displayOffObservedAt = AtomicLong()
            val cancellationObservedAt = AtomicLong()
            val cancellationReceived = CountDownLatch(1)
            val cancellationFailure = AtomicReference<String>()
            val mainHandler = Handler(Looper.getMainLooper())
            val observeCancellation = object : Runnable {
                private var lastInteractive: Boolean? = null
                private var lastDisplayState = Display.STATE_UNKNOWN
                override fun run() {
                    if (glowDispatchedAt.get() == 0L || cancellationReceived.count == 0L) return
                    mainHandler.removeCallbacks(this)
                    val interactive = power.isInteractive
                    val displayState = display.state
                    val now = SystemClock.uptimeMillis()
                    if (interactive != lastInteractive || displayState != lastDisplayState) {
                        Log.i("CarbonPusherTest", "screen-off power receipt uptime=$now interactive=$interactive display=$displayState ${scene.describe()}")
                        lastInteractive = interactive
                        lastDisplayState = displayState
                    }
                    // This deadline precedes the actual lumeOff deadline: deliveredAt was
                    // recorded before the real UP handler scheduled its three-second callback.
                    if (now - glowDispatchedAt.get() >= CarbonLumeState.DURATION_MILLIS) {
                        cancellationFailure.set("No actual display-off/cancellation receipt before natural expiry: interactive=$interactive display=$displayState ${scene.describe()}")
                        cancellationReceived.countDown()
                        return
                    }
                    val actuallyOff = !interactive && displayState == Display.STATE_OFF
                    if (actuallyOff) displayOffObservedAt.compareAndSet(0, now)
                    if (actuallyOff && !home(scene).lumeActive && !home(scene).isLumeCallbackScheduled && !home(scene).isFrameScheduled && !home(scene).isRenderingVisible) {
                        cancellationObservedAt.set(now)
                        cancellationReceived.countDown()
                        Log.i("CarbonPusherTest", "screen-off cancellation receipt ${scene.describe()}")
                    } else {
                        // Display/visibility delivery order is not an assertion. Observe their
                        // real effects without depending on the shell caller or a deferred broadcast.
                        mainHandler.postDelayed(this, 20)
                    }
                }
            }
            val displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == display.displayId) observeCancellation.run()
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        screenOffReceivedAt.compareAndSet(0, SystemClock.uptimeMillis())
                        observeCancellation.run()
                        Log.i("CarbonPusherTest", "screen-off broadcast ${scene.describe()}")
                    }
                }
            }
            instrumentation.runOnMainSync {
                assertTrue("Screen-off fixture must start interactive", power.isInteractive)
                assertEquals("Screen-off fixture must start with its actual display on", Display.STATE_ON, display.state)
                assertEquals(Display.DEFAULT_DISPLAY, requireNotNull(scene.view.display).displayId)
                displays.registerDisplayListener(displayListener, mainHandler)
                // Android 14+ may defer/replace SCREEN_OFF while this host becomes cached.
                // Keep it as evidence, never as the low-latency power-state oracle.
                scene.context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            }
            val requestSleep = CountDownLatch(1)
            val sleepOutput = AtomicReference<ParcelFileDescriptor.AutoCloseInputStream>()
            val sleepCommand = FutureTask {
                Log.i("CarbonPusherTest", "screen-off command uptime=${SystemClock.uptimeMillis()}")
                // API 35's input script simply calls cmd input; avoid that extra shell wrapper.
                val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd input keyevent 223"))
                sleepOutput.set(output)
                try {
                    output.use { it.readBytes() }
                    Log.i("CarbonPusherTest", "screen-off command returned uptime=${SystemClock.uptimeMillis()}")
                }
                finally { sleepOutput.compareAndSet(output, null) }
            }
            // Arm this worker before touching the crown. It is signalled by actual UP delivery,
            // not by the much later return from the test's input transport.
            val worker = Thread({ requestSleep.await(); sleepCommand.run() }, "carbon-screen-off").apply { isDaemon = true; start() }
            try {
                tap(scene, CarbonPusherAction.LUME) { receipt ->
                    assertTrue(home(scene).lumeActive)
                    assertTrue(home(scene).isLumeCallbackScheduled)
                    glowDispatchedAt.set(receipt.deliveredAt)
                    requestSleep.countDown()
                    mainHandler.post(observeCancellation)
                    Log.i("CarbonPusherTest", "screen-off signal ${scene.describe()}")
                }
                sleepCommand.get(5, TimeUnit.SECONDS)
                assertTrue("The actual power/display observer must record cancellation", cancellationReceived.await(5, TimeUnit.SECONDS))
                cancellationFailure.get()?.let { fail(it) }
                val displayOffAfter = displayOffObservedAt.get() - glowDispatchedAt.get()
                val cancelledAfter = cancellationObservedAt.get() - glowDispatchedAt.get()
                assertTrue("The device must actually become noninteractive with its display OFF before natural expiry: ${displayOffAfter}ms", displayOffAfter in 0 until CarbonLumeState.DURATION_MILLIS)
                assertTrue("Cancellation itself must be observed before natural expiry, not pass because of it: ${cancelledAfter}ms", cancelledAfter in 0 until CarbonLumeState.DURATION_MILLIS)
                instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "screen-off verified displayOffAfterMs=$displayOffAfter cancelledAfterMs=$cancelledAfter broadcastUptime=${screenOffReceivedAt.get()} ${scene.describe()}") }
            } finally {
                try {
                    sleepCommand.cancel(false)
                    requestSleep.countDown()
                    try { sleepOutput.getAndSet(null)?.close() }
                    finally { worker.join(5000) }
                } finally {
                    try {
                        instrumentation.runOnMainSync {
                            mainHandler.removeCallbacks(observeCancellation)
                            displays.unregisterDisplayListener(displayListener)
                            scene.context.unregisterReceiver(receiver)
                        }
                    } finally {
                        shell("input keyevent 224")
                        shell("wm dismiss-keyguard")
                    }
                }
                assertFalse("Screen-off command worker must terminate during cleanup", worker.isAlive)
            }
            await("Waking the test emulator must resume time without reviving glow") { power.isInteractive && display.state == Display.STATE_ON && home(scene).isRenderingVisible && !home(scene).lumeActive }
            instrumentation.runOnMainSync { Log.i("CarbonPusherTest", "screen-on verified ${scene.describe()}") }
        }
    }
}
