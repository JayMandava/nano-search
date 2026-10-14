package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.BuiltinWidget
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/** The launcher's own clock widget: the steel-cased analogue dial. */
object ClockWidgets {
    val all: List<BuiltinWidget> = listOf(SteelDial)

    private object SteelDial : BuiltinWidget {
        override val id = "steeldial"
        override val label = "Steel Dial"
        override val defaultHeightDp = 300
        override fun create(context: Context): View = SteelDialView(context)
        override fun preview(context: Context): Drawable = BitmapDrawable(context.resources, DialArt(context).picture(360, 10, 9, 38f))
    }
}
