package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.min

/** Original decorative framework, not a reconstruction of any proprietary movement. */
internal object CarbonBridgeGeometry {
    data class Boss(val x: Float, val y: Float, val radius: Float, val screwRadius: Float)
    val bosses: List<Boss> = listOf(
        Boss(418f, 333f, 13f, 7.2f), Boss(584f, 331f, 12f, 6.8f),
        Boss(303f, 579f, 13f, 7.4f), Boss(686f, 578f, 12f, 6.8f),
        Boss(419f, 748f, 12f, 6.8f), Boss(583f, 747f, 11f, 6.3f),
    )

    /** A union removes fake seams: the fork, counter surrounds and lower feet are one frame. */
    fun frame(): Path {
        val frame = Path()
        fun join(path: Path) { check(frame.op(path, Path.Op.UNION)) }
        fun branch(vararg points: Float) = join(rounded(points, 7f))
        fun circle(x: Float, y: Float, radius: Float) = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }

        // The two tapered upper arms meet below an off-centre, open fork, not a crossing X.
        branch(412f,229f, 438f,232f, 424f,315f, 433f,341f, 472f,375f,
            505f,395f, 539f,373f, 567f,337f, 575f,233f, 600f,236f,
            595f,341f, 568f,389f, 526f,420f, 502f,438f, 478f,422f,
            420f,378f, 400f,343f, 401f,314f)
        // A short unequal shoulder, rather than a repeated horizontal plate beneath twelve.
        branch(424f,339f, 449f,338f, 481f,350f, 522f,355f, 521f,369f,
            478f,369f, 449f,357f, 424f,352f)
        branch(487f,407f, 502f,422f, 461f,435f, 438f,414f, 421f,398f,
            420f,381f, 439f,387f, 469f,410f)
        branch(516f,409f, 542f,392f, 571f,377f, 585f,382f, 580f,398f,
            558f,415f, 537f,435f, 508f,432f)

        join(circle(350f, 465f, 109f))
        join(circle(650f, 465f, 109f))
        join(circle(500f, 693f, 96f))
        // Short bearing arms touch counter exteriors; they never run across counter interiors.
        branch(448f,486f, 456f,469f, 485f,486f, 505f,491f, 507f,508f, 479f,509f)
        branch(493f,492f, 518f,482f, 544f,472f, 553f,489f, 521f,504f, 503f,511f)
        join(circle(500f, 500f, 22f))

        // Unequal lower cells: a broad swept left opening and a narrower, angled right opening.
        branch(301f,552f, 327f,557f, 364f,591f, 396f,639f, 416f,680f,
            432f,740f, 415f,774f, 393f,765f, 401f,736f, 386f,686f,
            363f,643f, 335f,611f, 307f,590f, 289f,578f)
        branch(698f,557f, 711f,568f, 685f,599f, 660f,626f, 624f,663f,
            600f,698f, 581f,758f, 566f,772f, 551f,760f, 571f,698f,
            600f,653f, 641f,614f, 661f,587f)
        branch(484f,510f, 501f,517f, 475f,569f, 458f,610f, 444f,630f,
            431f,618f, 443f,590f, 457f,549f)
        branch(516f,510f, 533f,523f, 554f,563f, 565f,600f, 572f,626f,
            555f,635f, 544f,608f, 535f,575f, 510f,535f)
        bosses.forEach { join(circle(it.x, it.y, it.radius)) }

        // Counter and date clearances are cut after union, so no branch can invade their faces.
        val apertures = Path().apply {
            addCircle(350f, 465f, 102f, Path.Direction.CW)
            addCircle(650f, 465f, 102f, Path.Direction.CW)
            addCircle(500f, 693f, 88f, Path.Direction.CW)
            addRoundRect(333f, 263f, 391f, 364f, 7f, 7f, Path.Direction.CW)
            addCircle(500f, 500f, 7f, Path.Direction.CW)
        }
        check(frame.op(apertures, Path.Op.DIFFERENCE))
        check(frame.op(CarbonPusherGeometry.innerDialPath(), Path.Op.INTERSECT))
        return frame
    }

    /** Round the inner and outer corners with finite quadratic shoulders, not paint join tricks. */
    private fun rounded(points: FloatArray, radius: Float): Path {
        require(points.size >= 6 && points.size % 2 == 0)
        val count = points.size / 2
        val incoming = FloatArray(points.size)
        val outgoing = FloatArray(points.size)
        for (i in 0 until count) {
            val previous = (i + count - 1) % count
            val next = (i + 1) % count
            val x = points[i * 2]; val y = points[i * 2 + 1]
            val px = points[previous * 2] - x; val py = points[previous * 2 + 1] - y
            val nx = points[next * 2] - x; val ny = points[next * 2 + 1] - y
            val before = hypot(px, py); val after = hypot(nx, ny)
            val inset = min(radius, min(before, after) * .38f)
            require(before > 0f && after > 0f)
            incoming[i * 2] = x + px / before * inset
            incoming[i * 2 + 1] = y + py / before * inset
            outgoing[i * 2] = x + nx / after * inset
            outgoing[i * 2 + 1] = y + ny / after * inset
        }
        return Path().apply {
            moveTo(incoming[0], incoming[1])
            for (i in 0 until count) {
                lineTo(incoming[i * 2], incoming[i * 2 + 1])
                quadTo(points[i * 2], points[i * 2 + 1], outgoing[i * 2], outgoing[i * 2 + 1])
            }
            close()
        }
    }
}

/** Baked into Carbon's static overlay; no path construction or material work occurs per frame. */
internal class CarbonBridgeArt {
    private val frame = CarbonBridgeGeometry.frame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = LinearGradient(320f, 235f, 690f, 785f,
        intArrayOf(0xff657075.toInt(), 0xff343e43.toInt(), 0xff556167.toInt(), 0xff293338.toInt()), null, Shader.TileMode.CLAMP)
    private val bevel = LinearGradient(300f, 225f, 720f, 780f,
        intArrayOf(0xffaab5b5.toInt(), 0xff465256.toInt(), 0xffbac4bf.toInt(), 0xff3d4c52.toInt()), null, Shader.TileMode.CLAMP)

    fun draw(canvas: Canvas) {
        recessedLattice(canvas, 675f, 267f, .92f, -13f, 4)
        recessedLattice(canvas, 301f, 657f, .83f, 7f, 3)
        recessedLattice(canvas, 690f, 677f, .68f, -18f, 3)
        val shadow = canvas.save()
        canvas.translate(1.5f, 2.5f)
        paint.color = 0xb8000000.toInt(); paint.shader = null; paint.style = Paint.Style.FILL
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(shadow)
        // The bevel is substantially narrower and darker than the unchanged counter rims.
        paint.color = Color.WHITE; paint.shader = bevel; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(frame, paint)
        paint.shader = body; paint.style = Paint.Style.FILL
        canvas.drawPath(frame, paint)
        paint.shader = null
        val grain = canvas.save()
        canvas.clipPath(frame)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .35f
        for (i in 0..210) {
            paint.color = if (i % 4 == 0) 0x265e747d else 0x14202e34
            val y = 230f + i * 2.7f
            canvas.drawLine(235f, y, 770f, y - 2f, paint)
        }
        canvas.restoreToCount(grain)
        // Recessed seats are centred on real support material, not floating surface screws.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = 0xff202b31.toInt()
        CarbonBridgeGeometry.bosses.forEach { canvas.drawCircle(it.x, it.y, it.screwRadius + 2.4f, paint) }
        paint.style = Paint.Style.FILL
    }

    private fun recessedLattice(canvas: Canvas, x: Float, y: Float, scale: Float, degrees: Float, crossBars: Int) {
        val saved = canvas.save()
        canvas.translate(x, y); canvas.scale(scale, scale); canvas.rotate(degrees)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND; paint.color = 0xff202b30.toInt()
        for (i in 0..3) {
            val px = -30f + i * 19f
            canvas.drawLine(px, -56f, px + 8f, 54f, paint)
        }
        paint.strokeWidth = 4f
        for (i in 0 until crossBars) {
            val py = -35f + i * 25f
            canvas.drawLine(-35f, py, 36f, py - 8f, paint)
        }
        // One restrained edge cue; no repeated bright chamfer around every cell.
        paint.strokeWidth = .7f; paint.color = 0xff3c484d.toInt()
        canvas.drawLine(-30f, -56f, -22f, 54f, paint)
        canvas.restoreToCount(saved)
        paint.style = Paint.Style.FILL
    }
}
