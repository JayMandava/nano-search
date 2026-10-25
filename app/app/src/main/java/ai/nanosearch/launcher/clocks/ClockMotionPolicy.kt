package ai.nanosearch.launcher.clocks

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/** Observe policy changes while attached; rendering reads cached booleans without service IPC. */
class ClockMotionPolicy(context: Context, private val onChanged: () -> Unit = {}) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nano", Context.MODE_PRIVATE)
    private val power = app.getSystemService(PowerManager::class.java)
    private var attached = false
    private var reduced = false
    private var animatorsEnabled = true
    private var powerSave = false
    val smooth: Boolean get() = shouldAnimate(true, true, reduced, animatorsEnabled, powerSave)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == REDUCED_MOTION_KEY) refresh()
    }
    private val powerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val animationChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun attach() {
        if (attached) return
        attached = true
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        app.registerReceiver(powerChanged, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationChanged)
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        app.unregisterReceiver(powerChanged)
        app.contentResolver.unregisterContentObserver(animationChanged)
    }

    fun refresh() {
        reduced = prefs.getBoolean(REDUCED_MOTION_KEY, false)
        animatorsEnabled = Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f && ValueAnimator.areAnimatorsEnabled()
        powerSave = power?.isPowerSaveMode == true
        onChanged()
    }

    fun frameDelayMillis(now: Long): Long = frameDelayMillis(smooth, now)

    companion object {
        const val REDUCED_MOTION_KEY = "clocks_reduced_motion"
        fun shouldAnimate(attached: Boolean, visible: Boolean, reduced: Boolean, animatorsEnabled: Boolean, powerSave: Boolean): Boolean =
            attached && visible && !reduced && animatorsEnabled && !powerSave
        fun frameDelayMillis(smooth: Boolean, now: Long): Long =
            if (smooth) 16L else 1000L - Math.floorMod(now, 1000L)
    }
}
