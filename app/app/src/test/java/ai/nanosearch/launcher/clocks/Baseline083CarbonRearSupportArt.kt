package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sqrt

/** Four visibly matched rear plates: shared side walls, web width and 45-degree shoulders. */
internal object Baseline083CarbonRearSupportGeometry {
    data class Panel(val mirrorX: Boolean, val top: Float, val bottom: Float)

    const val LEFT = 250f
    const val RIGHT = 445f
    const val OUTER_CORNER = 28f
    const val WEB_WIDTH = 14f
    // Offset the 45-degree facets by the same normal distance as the straight walls.
    val INNER_CORNER = OUTER_CORNER + WEB_WIDTH * (sqrt(2f) - 2f)

    // The upper and lower pairs share the same horizontal datum. Only their height follows
    // the available spaces between the unchanged registers; neither pair rotates or skews.
    val panels = listOf(
        Panel(false, 210f, 398f), Panel(true, 210f, 398f),
        Panel(false, 552f, 800f), Panel(true, 552f, 800f),
    )

    fun panelPath(panel: Panel): Path {
        fun octagon(left: Float, top: Float, right: Float, bottom: Float, corner: Float) = Path().apply {
            moveTo(left + corner, top); lineTo(right - corner, top)
            lineTo(right, top + corner); lineTo(right, bottom - corner)
            lineTo(right - corner, bottom); lineTo(left + corner, bottom)
            lineTo(left, bottom - corner); lineTo(left, top + corner); close()
        }
        val result = octagon(LEFT, panel.top, RIGHT, panel.bottom, OUTER_CORNER)
        val window = octagon(LEFT + WEB_WIDTH, panel.top + WEB_WIDTH,
            RIGHT - WEB_WIDTH, panel.bottom - WEB_WIDTH, INNER_CORNER)
        check(result.op(window, Path.Op.DIFFERENCE))
        if (panel.mirrorX) result.transform(Matrix().apply { setScale(-1f, 1f, 500f, 500f) })
        return result
    }

    fun frame(): Path {
        val result = Path()
        panels.forEach { check(result.op(panelPath(it), Path.Op.UNION)) }
        val inside = CarbonPusherGeometry.innerDialPath().apply {
            transform(Matrix().apply { setScale(.99f, .99f, 500f, 500f) })
        }
        check(result.op(inside, Path.Op.INTERSECT))
        // The lower register remains independent. These rear frames cannot replace the
        // removed front neck or intrude into counter, date or spindle apertures.
        val openings = Path().apply {
            addCircle(350f, 465f, 103f, Path.Direction.CW)
            addCircle(650f, 465f, 103f, Path.Direction.CW)
            addCircle(500f, 693f, 89f, Path.Direction.CW)
            addRoundRect(332f, 262f, 392f, 365f, 8f, 8f, Path.Direction.CW)
            addCircle(500f, 500f, 8f, Path.Direction.CW)
            addRect(482f, 563f, 518f, 596f, Path.Direction.CW)
        }
        check(result.op(openings, Path.Op.DIFFERENCE))
        return result
    }
}

/** Cached static rear layer, underneath the unchanged animated wheels and front bridge. */
internal class Baseline083CarbonRearSupportArt {
    private val frame = Baseline083CarbonRearSupportGeometry.frame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas) {
        val shadow = canvas.save()
        canvas.translate(1f, 1.5f)
        paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt()
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(shadow)
        paint.color = 0xff2b3537.toInt()
        canvas.drawPath(frame, paint)
        val edge = canvas.save()
        canvas.clipPath(frame)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f
        paint.color = 0xff64716f.toInt()
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(edge)
        paint.style = Paint.Style.FILL
    }
}
