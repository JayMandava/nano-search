package ai.nanosearch.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View

/**
 * A widget drawn by the launcher itself instead of by another app. The clocks are its own; it also looks for an optional
 * `ai.nanosearch.launcher.personal.PersonalWidgets` class (see [BuiltinWidgets]) and offers whatever that provides in the widget picker.
 */
interface BuiltinWidget {
    val id: String
    val label: String
    /** Optional concise attribution shown only in selection UI. */
    val credit: String? get() = null
    val defaultHeightDp: Int
    fun create(context: Context): View
    /** A picture for the widget picker, or null. */
    fun preview(context: Context): Drawable?
}

object BuiltinWidgets {
    /** The launcher's own clocks, then any extras from the optional personal source set (a local build only). */
    val all: List<BuiltinWidget> by lazy {
        ai.nanosearch.launcher.clocks.ClockWidgets.all + extras()
    }

    private fun extras(): List<BuiltinWidget> =
        runCatching {
            val cls = Class.forName("ai.nanosearch.launcher.personal.PersonalWidgets")
            @Suppress("UNCHECKED_CAST")
            cls.getMethod("all").invoke(cls.getField("INSTANCE").get(null)) as List<BuiltinWidget>
        }.getOrDefault(emptyList())

    fun find(id: String) = all.firstOrNull { it.id == id }
}
