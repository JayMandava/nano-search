package ai.nanosearch.launcher

import android.content.Context

/** Which apps were opened lately and which the user starred. Kept in the launcher's preferences, on the phone only. */
object AppUsage {
    private const val MAX_RECENT = 12
    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)

    /** Component names with the time they were last opened, newest first. */
    fun recents(c: Context): List<Pair<String, Long>> =
        prefs(c).getString("recents", "")!!.split(';').filter { it.contains('@') }.map { it.substringBeforeLast('@') to (it.substringAfterLast('@').toLongOrNull() ?: 0L) }

    fun note(c: Context, component: String) {
        if (component.startsWith(c.packageName)) return
        val list = (listOf(component to System.currentTimeMillis()) + recents(c).filter { it.first != component }).take(MAX_RECENT)
        prefs(c).edit().putString("recents", list.joinToString(";") { "${it.first}@${it.second}" }).apply()
    }

    fun forget(c: Context, component: String) {
        prefs(c).edit().putString("recents", recents(c).filter { it.first != component }.joinToString(";") { "${it.first}@${it.second}" }).apply()
    }

    fun favourites(c: Context): List<String> = prefs(c).getString("favourites", "")!!.split(';').filter { it.isNotBlank() }
    fun isFavourite(c: Context, component: String) = component in favourites(c)
    fun toggleFavourite(c: Context, component: String) {
        val now = favourites(c)
        prefs(c).edit().putString("favourites", (if (component in now) now - component else now + component).joinToString(";")).apply()
    }
}
