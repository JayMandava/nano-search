package ai.nanosearch.launcher

import android.content.Context
import android.util.Log

/**
 * The optional, experimental GPU backend (Vulkan). It is off until the user turns it on in Settings > Advanced, and it can only be
 * used if the GPU library loads, the driver reports a GPU, and a model has actually run on it once. The driver is untouched otherwise.
 *
 * Crash safety: a bad GPU driver can kill the app from native code. So before the first load and first generation on the GPU a
 * "pending" mark is written; if the app starts and finds it still there, the GPU crashed it, and is switched off for good.
 */
object GpuSupport {
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nano", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        if (prefs.getBoolean("gpu_pending", false)) {
            prefs.edit().putBoolean("gpu_pending", false).putBoolean("gpu_enabled", false).putString("gpu_blocked", "The GPU crashed the app once, so it stays off.").commit()
            Log.w("nanosearch", "GPU trial left pending: disabled")
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean("gpu_enabled", false) && blocked == null
        set(value) { prefs.edit().putBoolean("gpu_enabled", value).apply(); if (value) prefs.edit().remove("gpu_blocked").apply() }

    /** Why the GPU cannot be used, or null. Cleared when the user switches it on again. */
    var blocked: String?
        get() = prefs.getString("gpu_blocked", null)
        private set(value) { prefs.edit().putString("gpu_blocked", value).apply() }

    fun block(reason: String) { blocked = reason; prefs.edit().putBoolean("gpu_enabled", false).apply() }

    /** The first GPU line ("name | free/total"), read once per process and only while the GPU is switched on. */
    private var deviceLine: String? = null
    private var deviceRead = false
    val device: String?
        get() {
            if (!enabled || !NativeLlmGpu.present) return null
            if (!deviceRead) {
                deviceRead = true
                deviceLine = runCatching { NativeLlmGpu().gpuInfo().lines().firstOrNull { it.isNotBlank() } }.getOrNull()
            }
            return deviceLine
        }

    /** True when a GPU plan may be offered: switched on, library present, and the driver reports a GPU. */
    val usable get() = enabled && device != null

    val deviceName get() = device?.substringBefore(" | ")

    /** Runs [block] (the first load and generation on the GPU) with the crash mark set. Already-verified GPUs skip the mark. */
    fun <T> guarded(block: () -> T): T {
        if (verified) return block()
        prefs.edit().putBoolean("gpu_pending", true).commit()
        val result = block()
        prefs.edit().putBoolean("gpu_pending", false).commit()
        return result
    }

    private fun verifiedKey() = CpuPlan.tuneKey()
    val verified get() = prefs.getString("gpu_verified", null) == verifiedKey()
    fun markVerified() = prefs.edit().putString("gpu_verified", verifiedKey()).apply()
}
