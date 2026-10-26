package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.BuiltinWidget
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/** Every registered clock is offered without changing existing saved built-in widget IDs. */
object ClockWidgets {
    val all: List<BuiltinWidget> get() = ClockFaces.all.map { forFace(it.id) }
    fun forFace(id: String): BuiltinWidget {
        val definition = ClockFaces.all.firstOrNull { it.id == id }
            ?: ClockFaceDefinition(id, ClockFaces.labelFor(id), 300)
        return object : BuiltinWidget {
            override val id = definition.id
            override val label = definition.label
            override val credit = definition.credit
            override val defaultHeightDp = definition.defaultHeightDp
            override fun create(context: Context): View = ClockFaceView(context, id)
            override fun create(context: Context, instanceKey: String): View =
                if (id == ClockFaces.CARBON_ID) CarbonHomeClockView(context, instanceKey)
                else create(context)
            override fun preview(context: Context): Drawable? =
                // This legacy picker binds synchronously. Pack detail/grid previews use
                // the real renderer on a worker; never decode untrusted artwork here.
                if (ClockFaces.isPackId(id) && android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    context.getDrawable(ai.nanosearch.launcher.R.drawable.ic_menu_clock)
                else BitmapDrawable(context.resources, ClockFaces.preview(context, id))
        }
    }
}
