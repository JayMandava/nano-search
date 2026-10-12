package ai.nanosearch.launcher

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The clock live wallpaper is optional: the launcher only offers it when the build includes it (it lives in the `clocks` package,
 * next to the clocks it draws). These are the choices it reads from the launcher's preferences, and the way to open Android's own live wallpaper preview for it.
 */
object LockClock {
    private const val SERVICE = "ai.nanosearch.launcher.clocks.ClockWallpaperService"

    val CLOCKS = listOf("steel" to "Steel Dial", "flip" to "Flip clock")
    val BACKGROUNDS = (ClassicWallpapers.ALL + SenseWallpapers.ALL).map { it.id to it.name } + ("black" to "Black")
    val POSITIONS = listOf("top" to "Top", "middle" to "Middle", "low" to "Low")

    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)
    fun component(c: Context) = ComponentName(c.packageName, SERVICE)
    fun available(c: Context) = runCatching { c.packageManager.getServiceInfo(component(c), 0) }.isSuccess

    fun clock(c: Context) = prefs(c).getString("lockclock_clock", "steel") ?: "steel"
    fun background(c: Context) = prefs(c).getString("lockclock_bg", "cobalt") ?: "cobalt"
    fun position(c: Context) = prefs(c).getString("lockclock_pos", "middle") ?: "middle"

    private fun next(options: List<Pair<String, String>>, current: String) = options[(options.indexOfFirst { it.first == current } + 1) % options.size].first
    fun cycleClock(c: Context) = prefs(c).edit().putString("lockclock_clock", next(CLOCKS, clock(c))).apply()
    fun cycleBackground(c: Context) = prefs(c).edit().putString("lockclock_bg", next(BACKGROUNDS, background(c))).apply()
    fun cyclePosition(c: Context) = prefs(c).edit().putString("lockclock_pos", next(POSITIONS, position(c))).apply()

    fun nameOf(options: List<Pair<String, String>>, key: String) = options.firstOrNull { it.first == key }?.second ?: key

    /** Android's preview for the wallpaper, with its own choice of home screen, lock screen or both. */
    fun openPicker(context: Context) {
        context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
