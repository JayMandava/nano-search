package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/** Original repeated machining pattern, registered to the unchanged decorative wheel centres. */
internal object BaselineAlignedCarbonRearSupportGeometry {
    data class Support(val x: Float, val y: Float, val wheelRadius: Float)

    const val RIB_PHASE_DEGREES = 22.5f
    const val RIB_STEP_DEGREES = 45f
    const val RIB_WIDTH = 6f
    const val INNER_RADIUS_SCALE = .62f
    const val OUTER_CLEARANCE = 10f

    // Same centres/radii as BaselineAlignedCarbonDialArt's existing train. No independent panel rotations,
    // stretching or skew: all four supports use the same angular datum and rib/slot rhythm.
    val supports = listOf(
        Support(446f, 270f, 97f), Support(624f, 296f, 67f),
        Support(358f, 632f, 83f), Support(655f, 639f, 77f),
    )

    fun panelPath(support: Support): Path {
        val inner = support.wheelRadius * INNER_RADIUS_SCALE
        val outer = support.wheelRadius + OUTER_CLEARANCE
        val result = Path()
        fun ring(radius: Float) {
            val band = Path().apply { addCircle(support.x, support.y, radius + RIB_WIDTH / 2, Path.Direction.CW) }
            check(band.op(Path().apply {
                addCircle(support.x, support.y, radius - RIB_WIDTH / 2, Path.Direction.CW)
            }, Path.Op.DIFFERENCE))
            check(result.op(band, Path.Op.UNION))
        }
        ring(inner); ring(outer)
        repeat(8) { index ->
            val angle = Math.toRadians((RIB_PHASE_DEGREES + index * RIB_STEP_DEGREES).toDouble())
            val ux = cos(angle).toFloat(); val uy = sin(angle).toFloat()
            val nx = -uy * RIB_WIDTH / 2; val ny = ux * RIB_WIDTH / 2
            val x1 = support.x + inner * ux; val y1 = support.y + inner * uy
            val x2 = support.x + outer * ux; val y2 = support.y + outer * uy
            val rib = Path().apply {
                moveTo(x1 + nx, y1 + ny); lineTo(x2 + nx, y2 + ny)
                lineTo(x2 - nx, y2 - ny); lineTo(x1 - nx, y1 - ny); close()
            }
            check(result.op(rib, Path.Op.UNION))
        }
        return result
    }

    fun frame(): Path {
        val result = Path()
        supports.forEach { check(result.op(panelPath(it), Path.Op.UNION)) }
        val inside = CarbonPusherGeometry.innerDialPath().apply {
            transform(Matrix().apply { setScale(.99f, .99f, 500f, 500f) })
        }
        check(result.op(inside, Path.Op.INTERSECT))
        // Keep even this lower layer out of the functional openings. The centre-to-six gap
        // remains clear; no rear rib substitutes for the removed front neck.
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

/** Cached with the static base bitmap; no geometry generation occurs during a live frame. */
internal class BaselineAlignedCarbonRearSupportArt {
    private val frame = BaselineAlignedCarbonRearSupportGeometry.frame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas) {
        val shadow = canvas.save()
        canvas.translate(1f, 1.5f)
        paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt()
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(shadow)
        paint.color = 0xff242c2e.toInt()
        canvas.drawPath(frame, paint)
        val edge = canvas.save()
        canvas.clipPath(frame)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
        paint.color = 0xff414d4f.toInt()
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(edge)
        paint.style = Paint.Style.FILL
    }
}
