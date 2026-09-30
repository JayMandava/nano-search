package ai.nanosearch.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View

/**
 * A widget drawn by the launcher itself instead of by another app. The launcher ships none of its own; it looks for an optional
 * `ai.nanosearch.launcher.personal.PersonalWidgets` class (see [BuiltinWidgets]) and offers whatever that provides in the widget picker.
 */
interface BuiltinWidget {
    val id: String
    val label: String
    val defaultHeightDp: Int
    fun create(context: Context): View
    /** A picture for the widget picker, or null. */
    fun preview(context: Context): Drawable?
}

object BuiltinWidgets {
    /** Built-in widgets supplied by the optional personal source set; empty when it is not part of the build. */
    val all: List<BuiltinWidget> by lazy {
        runCatching {
            val cls = Class.forName("ai.nanosearch.launcher.personal.PersonalWidgets")
            @Suppress("UNCHECKED_CAST")
            cls.getMethod("all").invoke(cls.getField("INSTANCE").get(null)) as List<BuiltinWidget>
        }.getOrDefault(emptyList())
    }

    fun find(id: String) = all.firstOrNull { it.id == id }
}
