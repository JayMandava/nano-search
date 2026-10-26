package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.clocks.packs.FacePackException
import ai.nanosearch.launcher.clocks.packs.FacePackStore
import android.content.Context
import java.util.UUID

data class HomeClockPlacement(val pageKey: String, val token: String, val label: String, val faceId: String)
data class HomeClockPage(val key: String, val label: String)
data class ClockPackUsage(val homeCount: Int, val lockSelected: Boolean)

/** A face binding is separate from its stable widget token, size, position and chronograph identity. */
object PackClockApply {
    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)
    fun bindingKey(token: String) = "clock_face_$token"
    private fun widgetKey(page: String) = if (page == "0") "widgets" else "widgets_$page"
    private fun ids(c: Context) = prefs(c).getString("pages", "0").orEmpty().split(',').filter { it.isNotBlank() }.ifEmpty { listOf("0") }

    fun pages(context: Context): List<HomeClockPage> = ids(context).mapIndexed { index, id ->
        HomeClockPage(id, prefs(context).getString("pagename_$id", null)?.takeIf { it.isNotBlank() } ?: "Page ${index + 1}")
    }

    fun isClockToken(token: String): Boolean {
        val parts = token.split(':')
        return parts.size == 3 && parts[0] == "b" && parts[2].isNotBlank() &&
            (parts[1] == ClockFaces.STEEL_ID || parts[1] == ClockFaces.CARBON_ID || ClockFaces.isPackId(parts[1]))
    }

    fun faceForToken(context: Context, token: String): String = ClockFaces.normalizeSelection(
        prefs(context).getString(bindingKey(token), null) ?: token.split(':').getOrNull(1))

    fun homePlacements(context: Context): List<HomeClockPlacement> = pages(context).flatMap { page ->
        prefs(context).getString(widgetKey(page.key), "").orEmpty().split(',').filter(::isClockToken).mapIndexed { index, token ->
            val id = faceForToken(context, token)
            HomeClockPlacement(page.key, token, "${page.label} · Clock ${index + 1} · ${ClockFaces.labelFor(id)}", id)
        }
    }

    private fun <T> withAvailable(context: Context, id: String, selection: () -> T): T {
        if (id == ClockFaces.STEEL_ID || id == ClockFaces.CARBON_ID) return selection()
        if (!ClockFaces.isPackId(id)) throw FacePackException("That clock face is no longer installed")
        return FacePackStore.get(context).withAvailable(id) { selection() }
    }

    @Synchronized fun applyHome(context: Context, faceId: String, targetToken: String?, pageKey: String): String = withAvailable(context, faceId) {
        if (pageKey !in ids(context)) throw FacePackException("That Home page no longer exists")
        val preferences = prefs(context)
        val key = widgetKey(pageKey)
        val tokens = preferences.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }
        val token = targetToken ?: "b:$faceId:${UUID.randomUUID().toString().replace("-", "")}"
        if (targetToken != null && (targetToken !in tokens || !isClockToken(targetToken))) throw FacePackException("That Home clock no longer exists")
        val edit = preferences.edit().putString(bindingKey(token), faceId)
        if (targetToken == null) edit.putString(key, (tokens + token).joinToString(",")).putString("wsize_$token", "0,300")
        if (!edit.commit()) throw FacePackException("Couldn't save the Home clock choice")
        token
    }

    @Synchronized fun applyLock(context: Context, faceId: String) = withAvailable(context, faceId) {
        if (!prefs(context).edit().putString("lockclock_face", faceId).commit()) throw FacePackException("Couldn't save the Lock Clock choice")
    }

    fun removalUsage(context: Context, id: String) = ClockPackUsage(
        homePlacements(context).count { it.faceId == id }, prefs(context).getString("lockclock_face", ClockFaces.STEEL_ID) == id)

    /** Call only after the store commits removal. Tokens/layout and unrelated Carbon choices are untouched. */
    @Synchronized fun resetRemovedBindings(context: Context, id: String) {
        val p = prefs(context); val edit = p.edit()
        homePlacements(context).filter { it.faceId == id }.forEach { edit.putString(bindingKey(it.token), ClockFaces.STEEL_ID) }
        if (p.getString("lockclock_face", null) == id) edit.putString("lockclock_face", ClockFaces.STEEL_ID)
        if (!edit.commit()) throw FacePackException("Face removed; couldn't persist every fallback choice")
    }
}
