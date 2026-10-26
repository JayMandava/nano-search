package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarbonPusherGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var elapsed = 1000L
    private fun home(scene: CarbonPusherScene) = scene.view as CarbonHomeClockView
    private fun point(scene: CarbonPusherScene, action: CarbonPusherAction): PointF {
        lateinit var result: PointF
        instrumentation.runOnMainSync {
            val bounds = home(scene).targetLayout.boundsFor(action)
            result = scene.screenPoint(PointF(bounds.centerX(), bounds.centerY()))
        }
        return result
    }
    private fun tap(scene: CarbonPusherScene, action: CarbonPusherAction) {
        val start = SystemClock.uptimeMillis()
        TouchGesture(point(scene, action)).apply { send(MotionEvent.ACTION_DOWN); SystemClock.sleep(40); send(MotionEvent.ACTION_UP) }
        val dispatched = SystemClock.uptimeMillis()
        // Pointer injection already waits for input dispatch. An ordered UI-thread barrier observes
        // its result without waiting for animation idle, which can outlive the three-second glow.
        instrumentation.runOnMainSync {
            android.util.Log.i("CarbonPusherTest", "tap=$action dispatchMs=${dispatched - start} barrierMs=${SystemClock.uptimeMillis() - dispatched} focus=${scene.view.hasWindowFocus()} visible=${scene.view.isRenderingVisible} frame=${scene.view.isFrameScheduled} lume=${home(scene).lumeActive} expiry=${home(scene).isLumeCallbackScheduled} longPresses=${scene.longPresses}")
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
    private fun scene(block: (ActivityScenario<ClockTestHostActivity>, CarbonPusherScene) -> Unit) {
        elapsed = 1000L
        ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var scene: CarbonPusherScene
            scenario.onActivity { activity -> scene = CarbonPusherScene(activity, { CarbonHomeClockView(it, "home-test-instance", elapsedRealtime = { elapsed }) }) }
            // Layout visibility alone can precede input focus and the expensive first dial draw.
            // Inject only after the real window is focused and the first frame has completed.
            await("Real gesture fixture must have input focus and a completed first frame") {
                scene.view.hasWindowFocus() && scene.view.isRenderingVisible && scene.view.isFrameScheduled && scene.view.width > 0 && scene.view.height > 0
            }
            try { block(scenario, scene) } finally { scenario.onActivity { scene.detach(it) } }
        }
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
        tap(scene, CarbonPusherAction.OPEN_CLOCK)
        instrumentation.runOnMainSync { assertEquals(1, scene.context.clockLaunches.size); assertEquals(CarbonChronographState(), home(scene).chronographState) }
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
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                send(MotionEvent.ACTION_UP)
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
        tap(scene, CarbonPusherAction.START_PAUSE)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { assertFalse(home(scene).isFrameScheduled); elapsed = 61000 }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue(home(scene).chronographState.running); assertEquals(60000L, home(scene).chronographState.elapsed(elapsed)) }
        tap(scene, CarbonPusherAction.START_PAUSE)
        instrumentation.runOnMainSync { assertEquals(60000L, home(scene).chronographState.elapsed(elapsed)); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun crownConsumesItsOwnTapAndRepeatsWithoutChangingChronographOrPreferences() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.START_PAUSE)
        lateinit var before: CarbonChronographState
        lateinit var preferences: Map<String, *>
        instrumentation.runOnMainSync {
            elapsed = 2000
            before = home(scene).chronographState
            preferences = scene.context.getSharedPreferences(CarbonChronographStore.PREFS_FILE, android.content.Context.MODE_PRIVATE).all.toMap()
        }
        tap(scene, CarbonPusherAction.LUME)
        instrumentation.runOnMainSync {
            assertTrue(home(scene).lumeActive); assertEquals(1f, home(scene).lumeAlpha, 0f)
            assertEquals(before, home(scene).chronographState)
            assertEquals(preferences, scene.context.getSharedPreferences(CarbonChronographStore.PREFS_FILE, android.content.Context.MODE_PRIVATE).all)
            elapsed = 3500
            home(scene).invalidate()
        }
        instrumentation.runOnMainSync {}
        instrumentation.runOnMainSync { assertTrue(home(scene).lumeAlpha in 0.001f..0.999f) }
        tap(scene, CarbonPusherAction.LUME)
        instrumentation.runOnMainSync {
            assertEquals(1f, home(scene).lumeAlpha, 0f)
            assertEquals(before, home(scene).chronographState)
            assertEquals(2500L, home(scene).elapsedChronographMillis())
            elapsed = 6500
            home(scene).invalidate()
        }
        instrumentation.runOnMainSync {}
        instrumentation.runOnMainSync { assertFalse(home(scene).lumeActive); assertEquals(0f, home(scene).lumeAlpha, 0f); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun reducedMotionCrownIsStaticUntilExpiryThenOff() = scene { scenario, scene ->
        instrumentation.runOnMainSync { LockClock.setReducedMotion(scene.context, true) }
        await("Reduced-motion policy must be applied before the crown test") { !home(scene).isMotionEnabled }
        tap(scene, CarbonPusherAction.LUME)
        instrumentation.runOnMainSync { assertEquals(1f, home(scene).lumeAlpha, 0f); elapsed = 3999; home(scene).invalidate() }
        instrumentation.runOnMainSync {}
        instrumentation.runOnMainSync { assertEquals(1f, home(scene).lumeAlpha, 0f); elapsed = 4000; home(scene).invalidate() }
        instrumentation.runOnMainSync {}
        instrumentation.runOnMainSync { assertEquals(0f, home(scene).lumeAlpha, 0f); assertFalse(home(scene).lumeActive); assertTrue(scene.context.clockLaunches.isEmpty()) }
    }

    @Test fun hidingOrStoppingTheHostCancelsItsTransientGlow() = scene { scenario, scene ->
        tap(scene, CarbonPusherAction.LUME)
        instrumentation.runOnMainSync { assertTrue(home(scene).lumeActive); home(scene).visibility = View.GONE }
        await("A hidden home face must cancel its glow and callbacks") { !home(scene).lumeActive && !home(scene).isLumeCallbackScheduled && !home(scene).isFrameScheduled }
        instrumentation.runOnMainSync { home(scene).visibility = View.VISIBLE }
        await("Showing the face must resume ordinary time rendering") { home(scene).isRenderingVisible && home(scene).isFrameScheduled }
        tap(scene, CarbonPusherAction.LUME)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
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
            tap(scene, CarbonPusherAction.LUME)
            instrumentation.runOnMainSync { assertTrue(home(scene).lumeActive) }
            try {
                shell("input keyevent 223")
                await("Real screen-off must cancel glow and recurring callbacks") { !home(scene).lumeActive && !home(scene).isLumeCallbackScheduled && !home(scene).isFrameScheduled && !home(scene).isRenderingVisible }
            } finally {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
            await("Waking the test emulator must resume time without reviving glow") { home(scene).isRenderingVisible && !home(scene).lumeActive }
        }
    }
}
