package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import android.animation.ValueAnimator
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
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

    @Test fun tappingTheNativeFaceOpensTheActualSystemClock() {
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
                assertNotEquals(activity.packageName, expectedPackage)
                view = ClockFaceView(context, ClockFaces.CARBON_ID)
                activity.setContentView(view)
            }
            try {
                await("The native face must be visible before its click") { view.isRenderingVisible }
                scenario.onActivity { assertTrue(view.performClick()) }
                val deadline = SystemClock.uptimeMillis() + 5000
                var foreground: String? = null
                while (foreground != expectedPackage && SystemClock.uptimeMillis() < deadline) {
                    foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    if (foreground != expectedPackage) SystemClock.sleep(50)
                }
                assertEquals("Tapping the face must open system Clock, not Nano or Settings", expectedPackage, foreground)
            } finally {
                scenario.onActivity { activity ->
                    activity.startActivity(Intent(activity, ClockTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    activity.setContentView(FrameLayout(activity))
                }
                context.clean()
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
