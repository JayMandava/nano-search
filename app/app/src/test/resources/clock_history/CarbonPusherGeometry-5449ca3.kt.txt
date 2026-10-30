package ai.nanosearch.launcher.clocks

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.min

/** Hit regions use the same centered square as the artwork; case pushers are not assembly-scaled. */
object CarbonPusherGeometry {
    fun innerDialPath(): Path = Path().apply {
        moveTo(327f, 240f)
        quadTo(500f, 220f, 673f, 240f)
        cubicTo(724f, 290f, 766f, 405f, 760f, 500f)
        cubicTo(766f, 600f, 724f, 720f, 673f, 770f)
        quadTo(500f, 790f, 327f, 770f)
        cubicTo(276f, 720f, 234f, 600f, 240f, 500f)
        cubicTo(234f, 405f, 276f, 290f, 327f, 240f)
        close()
    }

    fun layout(width: Float, height: Float, density: Float): CarbonPusherLayout {
        val w = width.coerceAtLeast(0f); val h = height.coerceAtLeast(0f)
        val side = min(w, h)
        val x = (w - side) / 2f; val y = (h - side) / 2f
        val square = RectF(x, y, x + side, y + side)
        val minimum = 48f * density.coerceAtLeast(.1f)
        fun target(cx: Float, cy: Float, allowed: RectF): RectF {
            val tw = min(minimum, allowed.width().coerceAtLeast(0f))
            val th = min(minimum, allowed.height().coerceAtLeast(0f))
            val left = (cx - tw / 2f).coerceIn(allowed.left, (allowed.right - tw).coerceAtLeast(allowed.left))
            val top = (cy - th / 2f).coerceIn(allowed.top, (allowed.bottom - th).coerceAtLeast(allowed.top))
            return RectF(left, top, left + tw, top + th)
        }
        // Controls stay inside the square, outside the dial envelope, in disjoint vertical bands.
        val start = target(x + side * .905f, y + side * .339f, RectF(x + side * .785f, y, x + side, y + side * .420f))
        val reset = target(x + side * .905f, y + side * .668f, RectF(x + side * .785f, y + side * .580f, x + side, y + side))
        val split = target(x + side * .085f, y + side * .6915f, RectF(x, y, x + side * .215f, y + side))
        val crown = target(x + side * .950f, y + side * .500f, RectF(x + side * .785f, y + side * .420f, x + side, y + side * .580f))
        val path = innerDialPath()
        path.transform(Matrix().apply { setScale(CarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 500f, 500f) })
        path.transform(Matrix().apply { setScale(side / 1000f, side / 1000f); postTranslate(x, y) })
        val dialBounds = RectF().also { path.computeBounds(it, true) }
        val region = Region().apply { setPath(path, Region(0, 0, w.toInt(), h.toInt())) }
        return CarbonPusherLayout(square, dialBounds, start, split, reset, crown, region)
    }
}

class CarbonPusherLayout internal constructor(
    val square: RectF,
    val dialBounds: RectF,
    private val start: RectF,
    private val split: RectF,
    private val reset: RectF,
    private val crown: RectF,
    private val dial: Region,
) {
    fun boundsFor(action: CarbonPusherAction): RectF = RectF(when (action) {
        CarbonPusherAction.START_PAUSE -> start
        CarbonPusherAction.SPLIT_RELEASE -> split
        CarbonPusherAction.RESET -> reset
        CarbonPusherAction.OPEN_CLOCK -> dialBounds
        CarbonPusherAction.LUME -> crown
    })
    fun hitTest(x: Float, y: Float): CarbonPusherAction? = when {
        start.contains(x, y) -> CarbonPusherAction.START_PAUSE
        split.contains(x, y) -> CarbonPusherAction.SPLIT_RELEASE
        reset.contains(x, y) -> CarbonPusherAction.RESET
        crown.contains(x, y) -> CarbonPusherAction.LUME
        dial.contains(x.toInt(), y.toInt()) -> CarbonPusherAction.OPEN_CLOCK
        else -> null
    }
}
