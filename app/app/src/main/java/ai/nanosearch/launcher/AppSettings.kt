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
}
