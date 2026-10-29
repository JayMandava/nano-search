package ai.nanosearch.launcher.clocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

enum class BatteryBand(val angle: Float) { LOW(-122f), MEDIUM(-50f), HIGH(24f) }

object PhoneBattery {
    fun percent(level: Int, scale: Int, present: Boolean = true): Int? =
        if (!present || scale <= 0 || level < 0 || level > scale) null
        else (level.toLong() * 100L / scale).toInt()

    fun percent(intent: Intent?): Int? = intent?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }?.let {
        percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
    }

    fun band(percent: Int?): BatteryBand? = when (percent) {
        in 0..20 -> BatteryBand.LOW
        in 21..70 -> BatteryBand.MEDIUM
        in 71..100 -> BatteryBand.HIGH
        else -> null
    }

    fun read(context: Context): Int? = runCatching { percent(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }.getOrNull()
}

/** A sticky initial reading and event updates, only while its clock host is visible. */
internal class PhoneBatteryObserver(context: Context, private val changed: (Int?) -> Unit) {
    private val app = context.applicationContext
    private var started = false
    private var registered = false
    private var initialized = false
    var percent: Int? = null
        private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (registered) publish(PhoneBattery.percent(intent)) }
    }
    private fun publish(value: Int?) {
        if (!initialized || value != percent) { initialized = true; percent = value; changed(value) }
    }
    fun start() {
        if (started) return
        started = true; initialized = false; registered = true
        try {
            val sticky = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            publish(PhoneBattery.percent(sticky))
        } catch (_: RuntimeException) {
            registered = false
            publish(null) // Retry only on the next host activation, never every frame.
        }
    }
    fun stop() {
        started = false
        if (registered) { registered = false; app.unregisterReceiver(receiver) }
    }
}
