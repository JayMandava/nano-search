package ai.nanosearch.launcher

import android.app.ActivityManager
import android.content.Context

/**
 * How long models stay in memory after use, and whether the understanding and answering models may be loaded together. Auto scales with the
 * phone's RAM: generous on big phones (no reload pause), frugal on small ones. The user can force "keep loaded" or "free quickly".
 */
object MemoryPolicy {
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nano", Context.MODE_PRIVATE)

    fun init(context: Context) { app = context.applicationContext }

    val totalBytes: Long by lazy { ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }
    private val gb get() = totalBytes / 1e9

    /** "auto", "keep" or "quick". */
    var mode: String
        get() = prefs.getString("memory_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("memory_mode", value).apply()

    val parserIdleMs get() = when (mode) { "keep" -> 60 * 60_000L; "quick" -> 60_000L; else -> if (gb >= 11) 30 * 60_000L else if (gb >= 7) 5 * 60_000L else 2 * 60_000L }
    val answerIdleMs get() = when (mode) { "keep" -> 30 * 60_000L; "quick" -> 10_000L; else -> if (gb >= 11) 10 * 60_000L else if (gb >= 7) 30_000L else 15_000L }

    /** Both language models resident at once: only on phones with RAM to spare, whatever the mode. */
    val keepBoth get() = gb >= 11 && mode != "quick"
}
