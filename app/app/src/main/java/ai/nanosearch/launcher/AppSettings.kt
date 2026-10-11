package ai.nanosearch.launcher

import android.content.Context

/** The user's choices, kept in the same preferences file as the launcher's own state. */
object AppSettings {
    /** One search source the user can switch off: the index kind it fills, a label, and the permission it needs (null = none). */
    class Source(val kind: String, val label: String, val detail: String, val permission: String?)

    val SOURCES = listOf(
        Source("contact", "Contacts", "Names and numbers", android.Manifest.permission.READ_CONTACTS),
        Source("message", "Messages", "Text messages", android.Manifest.permission.READ_SMS),
        Source("call", "Calls", "Call history", android.Manifest.permission.READ_CALL_LOG),
        Source("event", "Calendar", "Events", android.Manifest.permission.READ_CALENDAR),
        Source("file", "Files", "Documents and downloads", null),
        Source("photo", "Photos", "Date, place, content and text in pictures", android.Manifest.permission.READ_MEDIA_IMAGES),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)

    fun sourceEnabled(c: Context, kind: String) = prefs(c).getBoolean("source_$kind", true)
    fun setSourceEnabled(c: Context, kind: String, on: Boolean) = prefs(c).edit().putBoolean("source_$kind", on).apply()

    /** "system", "light" or "dark". */
    fun themeMode(c: Context) = prefs(c).getString("themeMode", "system") ?: "system"
    fun setThemeMode(c: Context, mode: String) = prefs(c).edit().putString("themeMode", mode).apply()

    /** Colours taken from the wallpaper (Material You) when true, the Nano Search violet when false. */
    fun dynamicColor(c: Context) = prefs(c).getBoolean("dynamicColor", true)
    fun setDynamicColor(c: Context, on: Boolean) = prefs(c).edit().putBoolean("dynamicColor", on).apply()

    /** "modern" (Material 3 Expressive, the default) or "classic": flat and dark with a blue accent, glossy drawn icons and a Hub. */
    fun style(c: Context) = prefs(c).getString("style", "modern") ?: "modern"
    fun isClassic(c: Context) = style(c) == "classic"
    fun setStyle(c: Context, style: String) = prefs(c).edit().putString("style", style).apply()

    // Features that Classic turns on by default but that work in either style. Once the user touches one, their choice wins.
    private fun feature(c: Context, key: String) = if (prefs(c).contains(key)) prefs(c).getBoolean(key, false) else isClassic(c)
    private fun setFeature(c: Context, key: String, on: Boolean) = prefs(c).edit().putBoolean(key, on).apply()

    /** The Hub: one list for messages, missed calls, events and notifications. */
    fun hub(c: Context) = feature(c, "hub")
    fun setHub(c: Context, on: Boolean) = setFeature(c, "hub", on)

    /** A small dot or count on an app's icon while it has notifications. */
    fun dots(c: Context) = feature(c, "dots")
    fun setDots(c: Context, on: Boolean) = setFeature(c, "dots", on)

    /** Active Frames: the apps you used last, as live tiles on the first home page. */
    fun frames(c: Context) = feature(c, "frames")
    fun setFrames(c: Context, on: Boolean) = setFeature(c, "frames", on)

    /** Full screen: the status bar and navigation bar are hidden and slide in when you swipe from the top or bottom edge. */
    fun fullScreen(c: Context) = prefs(c).getBoolean("fullScreen", false)
    fun setFullScreen(c: Context, on: Boolean) = prefs(c).edit().putBoolean("fullScreen", on).apply()

    /** A soft tick when the Hub opens, an item is dismissed or the list is cleared. Haptics are always on; this adds the sound. */
    fun hubSounds(c: Context) = prefs(c).getBoolean("hubSounds", false)
    fun setHubSounds(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hubSounds", on).apply()
}
