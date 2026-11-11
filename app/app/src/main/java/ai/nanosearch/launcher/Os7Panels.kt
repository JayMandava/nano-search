package ai.nanosearch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Local-only app tray organisation. Removing a folder never hides or uninstalls its apps. */
object Os7Panels {
    enum class Panel(val title: String) {
        ALL("All"), FAVORITES("Favorites"), MEDIA("Media"), DOWNLOADS("Downloads"), FREQUENT("Frequent")
    }

    data class App(val component: String, val title: String, val category: Int = -1, val isSystem: Boolean = false)
    data class Folder(val id: String, val name: String, val components: List<String>)

    private fun prefs(c: Context) = c.getSharedPreferences("nano_os7_panels", Context.MODE_PRIVATE)

    /** Missing packages return null, so stale index entries are not presented as downloaded apps. */
    fun describe(c: Context, component: String, title: String): App? {
        val name = ComponentName.unflattenFromString(component) ?: return null
        if (name.packageName == c.packageName) return null
        return try {
            val info = c.packageManager.getApplicationInfo(name.packageName, PackageManager.ApplicationInfoFlags.of(0))
            App(component, title, info.category,
                info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    // Conservative package fallback for apps which omit Android's category metadata.
    private val mediaPackages = setOf(
        "com.android.camera", "com.android.camera2", "com.android.gallery3d", "com.android.music",
        "com.google.android.GoogleCamera", "com.google.android.apps.photos", "com.google.android.youtube",
        "com.google.android.apps.youtube.music", "com.spotify.music", "org.videolan.vlc",
        "com.sec.android.app.camera", "com.sec.android.gallery3d"
    )

    fun isMedia(app: App): Boolean = app.category in setOf(
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE
    ) || app.component.substringBefore('/') in mediaPackages

    /** Frequent is count-ranked; the other panels respect saved order then stable alphabetical order. */
    fun select(
        panel: Panel,
        apps: List<App>,
        favorites: List<String> = emptyList(),
        counts: Map<String, Long> = emptyMap(),
        order: List<String> = emptyList()
    ): List<App> {
        val starred = favorites.toSet()
        val positions = order.distinct().withIndex().associate { it.value to it.index }
        val alphabetical = compareBy<App> { it.title.lowercase(Locale.ROOT) }.thenBy { it.component }
        val sorting = if (panel == Panel.FREQUENT) {
            compareByDescending<App> { counts[it.component] ?: 0L }.then(alphabetical)
        } else {
            compareBy<App> { positions[it.component] ?: Int.MAX_VALUE }.then(alphabetical)
        }
        return apps.distinctBy { it.component }.filter {
            when (panel) {
                Panel.ALL -> true
                Panel.FAVORITES -> it.component in starred
                Panel.MEDIA -> isMedia(it)
                Panel.DOWNLOADS -> !it.isSystem
                Panel.FREQUENT -> (counts[it.component] ?: 0L) > 0L
            }
        }.sortedWith(sorting)
    }

    fun visiblePanels(c: Context): List<Panel> = visiblePanels(prefs(c).getStringSet("hiddenPanels", emptySet()).orEmpty())

    internal fun visiblePanels(hidden: Set<String>): List<Panel> =
        Panel.entries.filter { it == Panel.ALL || it.name !in hidden }

    fun setPanelVisible(c: Context, panel: Panel, visible: Boolean) {
        val hidden = prefs(c).getStringSet("hiddenPanels", emptySet()).orEmpty().toMutableSet()
        if (visible || panel == Panel.ALL) hidden.remove(panel.name) else hidden.add(panel.name)
        prefs(c).edit().putStringSet("hiddenPanels", hidden).apply()
    }

    fun order(c: Context): List<String> = prefs(c).getString("order", "").orEmpty().split(';').filter { it.isNotBlank() }.distinct()

    /** Keeping missing entries lets temporarily hidden/unavailable apps retain their placement. */
    fun saveOrder(c: Context, components: List<String>) {
        prefs(c).edit().putString("order", mergeOrder(components, order(c)).joinToString(";")).apply()
    }

    internal fun mergeOrder(components: List<String>, previous: List<String>): List<String> =
        (components + previous).filter { it.isNotBlank() }.distinct()

    fun folders(c: Context): List<Folder> = decodeFolders(prefs(c).getString("folders", "[]").orEmpty())

    internal fun decodeFolders(raw: String): List<Folder> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            val name = entry.optString("name").trim()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val members = entry.optJSONArray("components") ?: JSONArray()
            Folder(id, name, (0 until members.length()).mapNotNull { members.optString(it).takeIf(String::isNotBlank) }.distinct())
        }.distinctBy { it.id }
    } catch (_: JSONException) {
        emptyList()
    }

    private fun saveFolders(c: Context, folders: List<Folder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(JSONObject().put("id", folder.id).put("name", folder.name).put("components", JSONArray(folder.components)))
        }
        prefs(c).edit().putString("folders", array.toString()).apply()
    }

    @Synchronized
    fun createFolder(c: Context, name: String, components: List<String> = emptyList()): Folder? {
        val title = name.trim().take(40).takeIf { it.isNotBlank() } ?: return null
        val folder = Folder(UUID.randomUUID().toString(), title, components.filter { it.isNotBlank() }.distinct())
        val moved = folder.components.toSet()
        saveFolders(c, folders(c).map { it.copy(components = it.components.filterNot(moved::contains)) } + folder)
        return folder
    }

    @Synchronized
    fun addToFolder(c: Context, folderId: String, component: String) {
        saveFolders(c, moveToFolder(folders(c), folderId, component))
    }

    internal fun moveToFolder(folders: List<Folder>, folderId: String, component: String): List<Folder> {
        if (component.isBlank() || folders.none { it.id == folderId }) return folders
        return folders.map {
            it.copy(components = if (it.id == folderId) (it.components + component).distinct() else it.components - component)
        }
    }

    @Synchronized
    fun removeFromFolder(c: Context, folderId: String, component: String) {
        saveFolders(c, folders(c).map { if (it.id == folderId) it.copy(components = it.components - component) else it })
    }

    @Synchronized
    fun renameFolder(c: Context, folderId: String, name: String) {
        saveFolders(c, renameFolder(folders(c), folderId, name))
    }

    internal fun renameFolder(folders: List<Folder>, folderId: String, name: String): List<Folder> {
        val title = name.trim().take(40).takeIf { it.isNotBlank() } ?: return folders
        return folders.map { if (it.id == folderId) it.copy(name = title) else it }
    }

    @Synchronized
    fun deleteFolder(c: Context, folderId: String) {
        saveFolders(c, folders(c).filterNot { it.id == folderId })
    }
}
