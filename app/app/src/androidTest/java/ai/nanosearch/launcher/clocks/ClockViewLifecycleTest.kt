package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockViewLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Suppress("DEPRECATION")
    @Test fun tappingTheNativeFaceOpensTheActualSystemClock() {
        // Connect before the click: connecting afterward can miss the window transition, and
        // rootInActiveWindow may be null while the old window is being replaced.
        val automation = instrumentation.uiAutomation
        val originalServiceFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
                lateinit var context: IsolatedClockContext
                lateinit var view: ClockFaceView
                lateinit var expectedPackage: String
                scenario.onActivity { activity ->
                    context = IsolatedClockContext(activity, "clock-tap-${UUID.randomUUID()}")
                    @Suppress("DEPRECATION")
                    val clock = activity.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                    assertNotNull("A system Clock handler must be present in the emulator", clock)
                    expectedPackage = clock!!.activityInfo.packageName
                    Log.i("ClockLaunchTest", "resolved=${clock.activityInfo.packageName}/${clock.activityInfo.name} uptime=${SystemClock.uptimeMillis()}")
                    assertNotEquals(activity.packageName, expectedPackage)
                    view = ClockFaceView(context, ClockFaces.CARBON_ID)
                    activity.setContentView(view)
                }
                try {
                    await("The native face must be visible before its click") { view.isRenderingVisible }
                    var launchReturnedAt = 0L
                    val opened = automation.executeAndWaitForEvent({
                        scenario.onActivity {
                            Log.i("ClockLaunchTest", "click uptime=${SystemClock.uptimeMillis()} focus=${view.hasWindowFocus()} visible=${view.isRenderingVisible}")
                            assertTrue(view.performClick())
                        }
                        launchReturnedAt = SystemClock.uptimeMillis()
                    }, { event ->
                        // This is emitted by the real destination window, not an intercepted Intent.
                        // Register before the click so even a transition during onActivity is retained.
                        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        if (windowChanged) Log.i("ClockLaunchTest", "window uptime=${SystemClock.uptimeMillis()} eventTime=${event.eventTime} package=${event.packageName} class=${event.className} id=${event.windowId} hostState=${scenario.state}")
                        windowChanged && event.packageName?.toString() == expectedPackage
                    }, 5000)
                    try {
                        assertEquals("Tapping the face must open an actual system Clock window, not Nano or Settings", expectedPackage, opened.packageName?.toString())
                        // Preserve the foreground assertion without assuming the new window's root
                        // can already be fetched during its transition. The package comes from the
                        // real event; its window ID must be the focused/active application window.
                        val deadline = launchReturnedAt + 5000
                        var clockFocused = false
                        do {
                            val windows = automation.windows
                            try {
                                clockFocused = windows.any { it.id == opened.windowId && it.isActive && it.isFocused }
                                Log.i("ClockLaunchTest", "focus uptime=${SystemClock.uptimeMillis()} expectedWindow=${opened.windowId} actual=${windows.filter { it.isActive || it.isFocused }.map { it.id }} clockFocused=$clockFocused hostState=${scenario.state}")
                            } finally { windows.forEach { it.recycle() } }
                            if (!clockFocused && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                        } while (!clockFocused && SystemClock.uptimeMillis() < deadline)
                        assertTrue("The actual system Clock window must become foreground after the tap", clockFocused)
                    } finally { opened.recycle() }
                } finally {
                    scenario.onActivity { activity ->
                        activity.startActivity(Intent(activity, ClockTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        activity.setContentView(FrameLayout(activity))
                    }
                    context.clean()
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
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

    @Test fun frameCallbacksStopWhileHiddenDetachedAndActivityStoppedThenResume() {
        ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var context: IsolatedClockContext
            lateinit var view: ClockFaceView
            scenario.onActivity { activity ->
                context = IsolatedClockContext(activity, "clock-view-${UUID.randomUUID()}")
                view = ClockFaceView(context, ClockFaces.CARBON_ID)
                activity.setContentView(view)
            }
            try {
                await("Attached visible Carbon must render and schedule its next time update") { view.isRenderingVisible && view.isFrameScheduled }
                scenario.onActivity { view.visibility = View.GONE }
                await("Hidden clocks must leave no recurring frame callback") { !view.isRenderingVisible && !view.isFrameScheduled }
                scenario.onActivity { view.visibility = View.VISIBLE }
                await("Showing the clock must resume time rendering") { view.isRenderingVisible && view.isFrameScheduled }
                scenario.onActivity { it.setContentView(FrameLayout(it)) }
                await("Detached clocks must cancel callbacks and observers") { !view.isAttachedToWindow && !view.isFrameScheduled }
                scenario.onActivity { it.setContentView(view) }
                await("Reattachment must rebuild released art and resume") { view.isRenderingVisible && view.isFrameScheduled }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await("A stopped host must leave no recurring frame callback") { !view.isRenderingVisible && !view.isFrameScheduled }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await("Foreground host must resume rendering") { view.isRenderingVisible && view.isFrameScheduled }
            } finally {
                scenario.onActivity { it.setContentView(FrameLayout(it)) }
                context.clean()
            }
        }
    }

    @Test fun preferenceChangesDisableSmoothMotionAndDetachedObserversStayReleased() {
        ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var context: IsolatedClockContext
            lateinit var view: ClockFaceView
            scenario.onActivity { activity ->
                context = IsolatedClockContext(activity, "clock-policy-${UUID.randomUUID()}")
                view = ClockFaceView(context, ClockFaces.STEEL_ID)
                activity.setContentView(view)
            }
            try {
                await("Initial native clock must attach") { view.isRenderingVisible && view.isFrameScheduled }
                scenario.onActivity { LockClock.setReducedMotion(context, true) }
                await("Reduced motion must disable sweeps while preserving accurate one-second updates") { !view.isMotionEnabled && view.isFrameScheduled }
                scenario.onActivity { it.setContentView(FrameLayout(it)); LockClock.setReducedMotion(context, false) }
                await("Detached policy must no longer react to preference callbacks") { !view.isAttachedToWindow && !view.isFrameScheduled && !view.isMotionEnabled }
                scenario.onActivity { it.setContentView(view) }
                await("Reattached policy must refresh current animation/power preferences") {
                    val expected = ValueAnimator.areAnimatorsEnabled() && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
                    view.isRenderingVisible && view.isFrameScheduled && view.isMotionEnabled == expected
                }
            } finally {
                scenario.onActivity { it.setContentView(FrameLayout(it)) }
                context.clean()
            }
        }
    }

    @Test fun changingFacesKeepsTheSameViewAndRefreshesItsAccessibleLabel() {
        ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var context: IsolatedClockContext
            lateinit var view: ClockFaceView
            scenario.onActivity { activity ->
                context = IsolatedClockContext(activity, "clock-selection-${UUID.randomUUID()}")
                view = ClockFaceView(context)
                activity.setContentView(view)
            }
            try {
                await("Default Steel label must be accessible") { view.contentDescription?.startsWith("Steel Dial,") == true }
                scenario.onActivity { view.faceId = ClockFaces.CARBON_ID }
                await("Changing to Carbon must retain the host and expose its current label/time") { view.faceId == ClockFaces.CARBON_ID && view.isRenderingVisible && view.contentDescription?.startsWith("Carbon 01,") == true }
                scenario.onActivity { view.faceId = "unknown-face" }
                await("Unknown view selections must retain the existing Steel default") { view.faceId == ClockFaces.STEEL_ID && view.contentDescription?.startsWith("Steel Dial,") == true }
            } finally {
                scenario.onActivity { it.setContentView(FrameLayout(it)) }
                context.clean()
            }
        }
    }
}
