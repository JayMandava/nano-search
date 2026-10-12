package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.BuiltinWidget
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View

/** The launcher's own clock widgets: the flip clock and the steel-cased analogue dial. */
object ClockWidgets {
    val all: List<BuiltinWidget> = listOf(FlipClock, SteelDial)

    private object FlipClock : BuiltinWidget {
        override val id = "flipclock"
        override val label = "Flip Clock"
        override val defaultHeightDp = 232
        override fun create(context: Context): View = FlipClockView(context, panel = true)
        override fun preview(context: Context): Drawable {
            val art = FlipFaces(context)
            val cw = 150; val ch = 150
            val bmp = Bitmap.createBitmap(cw * 2 + 40, ch + 20, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            art.drawRest(c, 10f, 10f, cw, ch, Face("10"))
            art.drawRest(c, 10f + cw + 10f, 10f, cw, ch, Face("21"))
            return BitmapDrawable(context.resources, bmp)
        }
    }

    private object SteelDial : BuiltinWidget {
        override val id = "steeldial"
        override val label = "Steel Dial"
        override val defaultHeightDp = 300
        override fun create(context: Context): View = SteelDialView(context)
        override fun preview(context: Context): Drawable = BitmapDrawable(context.resources, DialArt(context).picture(360, 10, 9, 38f))
    }
}
