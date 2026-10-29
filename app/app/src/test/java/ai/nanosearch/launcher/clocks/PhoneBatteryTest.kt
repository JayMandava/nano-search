package ai.nanosearch.launcher.clocks

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PhoneBatteryTest {
    private fun reading(level: Int, scale: Int = 100) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale).putExtra(BatteryManager.EXTRA_PRESENT, true)

    @Test fun exactInclusiveBandsAndInvalidReadings() {
        for ((value, band) in listOf(0 to BatteryBand.LOW, 20 to BatteryBand.LOW, 21 to BatteryBand.MEDIUM,
            70 to BatteryBand.MEDIUM, 71 to BatteryBand.HIGH, 100 to BatteryBand.HIGH)) {
            assertEquals(value, PhoneBattery.percent(reading(value)))
            assertEquals(band, PhoneBattery.band(value))
        }
        for (value in listOf(null, -1, 101, Int.MAX_VALUE)) assertNull(PhoneBattery.band(value))
        assertNull(PhoneBattery.percent(reading(40).apply {removeExtra(BatteryManager.EXTRA_PRESENT)}))
        assertNull(PhoneBattery.percent(null)); assertNull(PhoneBattery.percent(Intent()))
        assertNull(PhoneBattery.percent(reading(-1))); assertNull(PhoneBattery.percent(reading(101)))
        assertNull(PhoneBattery.percent(reading(1, 0))); assertNull(PhoneBattery.percent(reading(1, -1)))
        assertNull(PhoneBattery.percent(reading(40).putExtra(BatteryManager.EXTRA_PRESENT, false)))
        assertEquals(20, PhoneBattery.percent(2, 10)); assertEquals(100, PhoneBattery.percent(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun observerUsesEventsDeduplicatesAndStopsUntilRestarted() {
        val app = RuntimeEnvironment.getApplication()
        app.sendStickyBroadcast(reading(20))
        val values = mutableListOf<Int?>()
        val observer = PhoneBatteryObserver(app) { values += it }
        observer.start(); observer.start(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(20), values)
        app.sendBroadcast(reading(21)); shadowOf(Looper.getMainLooper()).idle()
        app.sendBroadcast(reading(21)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(20, 21), values)
        observer.stop(); observer.stop()
        app.sendStickyBroadcast(reading(71)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(20, 21), values)
        observer.start(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(71, observer.percent)
        app.sendBroadcast(reading(-1)); shadowOf(Looper.getMainLooper()).idle()
        assertNull(observer.percent); assertNull(values.last())
        observer.stop()
    }

    @Test fun clockViewSubscribesOnlyWhileVisibleAndDetachesCleanly() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val app = RuntimeEnvironment.getApplication()
        app.sendStickyBroadcast(reading(20))
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        val view = CarbonDialView(host.get())
        host.get().setContentView(view)
        host.visible()
        // Manifest-free Robolectric windows start hidden; model the framework's
        // visible-window state before delivering its visibility callback.
        val attach = org.robolectric.util.ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
        view.layout(0, 0, 360, 360); view.onVisibilityAggregated(true)
        shadowOf(Looper.getMainLooper()).idle()
        fun observation(): PhoneBatteryObserver = ClockFaceView::class.java.getDeclaredField("battery")
            .apply { isAccessible = true }.get(view) as PhoneBatteryObserver
        assertTrue("View must be attached for lifecycle test", view.isAttachedToWindow)
        assertEquals(20, observation().percent)
        view.onVisibilityAggregated(false)
        app.sendStickyBroadcast(reading(71)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(20, observation().percent)
        view.onVisibilityAggregated(true); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(71, observation().percent)
        fun renderedBand(): BatteryBand? {
            val renderer=ClockFaceView::class.java.getDeclaredField("renderer").apply {isAccessible=true}.get(view)
            return CarbonClockFaceRenderer::class.java.getDeclaredField("battery").apply {isAccessible=true}.get(renderer) as BatteryBand?
        }
        assertEquals(BatteryBand.HIGH, renderedBand())
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF));shadowOf(Looper.getMainLooper()).idle()
        app.sendStickyBroadcast(reading(21));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(71,observation().percent)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(21,observation().percent);assertEquals(BatteryBand.MEDIUM,renderedBand())
        app.sendBroadcast(reading(-1));shadowOf(Looper.getMainLooper()).idle()
        assertNull(observation().percent);assertNull(renderedBand())
        host.pause().stop().destroy()
        app.sendBroadcast(reading(0)); shadowOf(Looper.getMainLooper()).idle()
        assertNull(observation().percent)
    }
}
