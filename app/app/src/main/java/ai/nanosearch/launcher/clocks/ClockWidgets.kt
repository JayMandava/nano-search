package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.BuiltinWidget
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/** Every registered clock is offered without changing existing saved built-in widget IDs. */
object ClockWidgets {
    val all: List<BuiltinWidget> = ClockFaces.all.map { definition ->
        object : BuiltinWidget {
            override val id = definition.id
            override val label = definition.label
            override val credit = definition.credit
            override val defaultHeightDp = definition.defaultHeightDp
            override fun create(context: Context): View = ClockFaceView(context, id)
            override fun create(context: Context, instanceKey: String): View =
                if (id == ClockFaces.CARBON_ID) CarbonHomeClockView(context, instanceKey)
                else create(context)
            override fun preview(context: Context): Drawable = BitmapDrawable(context.resources, ClockFaces.preview(context, id))
        }
    }
}
