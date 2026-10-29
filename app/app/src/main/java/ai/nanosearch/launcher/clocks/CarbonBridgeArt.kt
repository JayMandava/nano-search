package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.min

/** Original decorative framework, not a reconstruction of any proprietary movement. */
internal object CarbonBridgeGeometry {
    /** The side-register frame is continuous; the lower register retains its separate collar. */
    fun frame(): Path {
        val frame = Path()
        fun join(path: Path) { check(frame.op(path, Path.Op.UNION)) }
        fun branch(vararg points: Float) = join(rounded(points, 2f))
        fun circle(x: Float, y: Float, radius: Float) = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }

        // The reference's upper spars spread OUT toward the counter shoulders. They do not
        // fold into the old rounded Y at the centre. Narrow faces leave the upper cell open.
        // Flare into the bezel, then ease into the existing straight spars. Mirroring
        // the complete contour keeps both shoulders on the same curved datum.
        val upper = Path().apply {
            moveTo(413f, 224f)
            cubicTo(425f, 226f, 440f, 228f, 451f, 229f)
            quadTo(438f, 246f, 433f, 272f)
            lineTo(415f, 352f); lineTo(403f, 376f)
            quadTo(393f, 379f, 383f, 379f)
            lineTo(398f, 348f); lineTo(415f, 268f)
            quadTo(422f, 240f, 413f, 224f); close()
        }
        join(upper)
        join(Path(upper).apply { transform(Matrix().apply { setScale(-1f, 1f, 500f, 500f) }) })

        join(circle(350f, 465f, 110f))
        join(circle(650f, 465f, 110f))
        join(circle(500f, 693f, 97f))

        // A crisp, open faceted collar ties the upper and lower counter tangencies together.
        // Cut its cell before unioning bearing arms: the cell must not sever their support.
        val collar = rounded(floatArrayOf(
            442f, 440f, 466f, 414f, 534f, 414f, 558f, 440f,
            577f, 464f, 577f, 512f, 549f, 546f, 521f, 562f,
            479f, 562f, 451f, 546f, 423f, 512f, 423f, 464f), 2f)
        check(collar.op(rounded(floatArrayOf(
            449f, 450f, 470f, 427f, 530f, 427f, 551f, 450f,
            564f, 474f, 564f, 507f, 540f, 537f, 518f, 549f,
            482f, 549f, 460f, 537f, 436f, 507f, 436f, 474f), 2f), Path.Op.DIFFERENCE))
        join(collar)
        // Short dark bearing webs support the hand spindle, without a competing bright X.
        branch(447f, 480f, 455f, 473f, 487f, 491f, 503f, 493f,
            503f, 505f, 482f, 506f)
        branch(497f, 493f, 513f, 491f, 545f, 473f, 553f, 480f,
            518f, 506f, 497f, 505f)
        branch(493f, 511f, 507f, 511f, 507f, 555f, 493f, 555f)
        join(circle(500f, 500f, 20f))

        // Long, almost straight lower spars replace the old broad hooked/S-shaped feet.
        val lower = Path().apply {
            moveTo(311f, 559f); lineTo(329f, 560f); lineTo(410f, 751f)
            quadTo(415f, 765f, 432f, 784f)
            lineTo(394f, 785f)
            quadTo(403f, 772f, 395f, 750f); close()
        }
        join(lower)
        join(Path(lower).apply { transform(Matrix().apply { setScale(-1f, 1f, 500f, 500f) }) })
        // The central octagon connects only the side registers. The six-o'clock register
        // retains its own surround, with a real open gap above it rather than a centre neck.

        // Clearances include a real margin beyond the required apertures: Path-op curves and
        // independently rasterized Region circles must stay disjoint even at 240-pixel size.
        val apertures = Path().apply {
            addCircle(350f, 465f, 103f, Path.Direction.CW)
            addCircle(650f, 465f, 103f, Path.Direction.CW)
            addCircle(500f, 693f, 89f, Path.Direction.CW)
            addRoundRect(332f, 262f, 392f, 365f, 8f, 8f, Path.Direction.CW)
            addCircle(500f, 500f, 8f, Path.Direction.CW)
            // Relieve the bottom collar without altering the counter itself. Do not leave
            // tangent hairline fragments where the annulus meets the curved dial envelope.
            addRect(448f, 765f, 552f, 825f, Path.Direction.CW)
        }
        check(frame.op(apertures, Path.Op.DIFFERENCE))
        // The actual dial/case path stays untouched. Give only the framework's edge a small
        // physical clearance so independent native curve rasters cannot protrude beyond it.
        val insideDial = CarbonPusherGeometry.innerDialPath().apply {
            transform(Matrix().apply { setScale(.99f, .99f, 500f, 500f) })
        }
        check(frame.op(insideDial, Path.Op.INTERSECT))
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
    private val rearSupports = CarbonRearSupportArt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = LinearGradient(320f, 235f, 690f, 785f,
        intArrayOf(0xff484b49.toInt(), 0xff303332.toInt(), 0xff454947.toInt(), 0xff292d2c.toInt()), null, Shader.TileMode.CLAMP)
    private val bevel = LinearGradient(300f, 225f, 720f, 780f,
        intArrayOf(0xffd1d5cc.toInt(), 0xff909b96.toInt(), 0xffc5ccc1.toInt(), 0xff747f79.toInt()), null, Shader.TileMode.CLAMP)

    /** Rear material is baked into the base, below the animated wheels, never over them. */
    fun drawRear(canvas: Canvas) = rearSupports.draw(canvas)

    fun draw(canvas: Canvas) {
        val shadow = canvas.save()
        canvas.translate(1.5f, 2.5f)
        paint.color = 0xb8000000.toInt(); paint.shader = null; paint.style = Paint.Style.FILL
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(shadow)
        paint.color = Color.WHITE; paint.shader = body; paint.style = Paint.Style.FILL
        canvas.drawPath(frame, paint)
        // A narrow machined edge is visibly separate from the flat graphite face. Clip the
        // chamfer into the support, so no bright stroke spills into protected apertures.
        val chamfer = canvas.save()
        canvas.clipPath(frame)
        paint.color = Color.WHITE; paint.shader = bevel; paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5.6f; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(frame, paint)
        canvas.restoreToCount(chamfer)
        paint.shader = null
        val grain = canvas.save()
        canvas.clipPath(frame)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .35f
        for (i in 0..210) {
            paint.color = if (i % 4 == 0) 0x185e6862 else 0x10202a25
            val y = 230f + i * 2.7f
            canvas.drawLine(235f, y, 770f, y - 2f, paint)
        }
        canvas.restoreToCount(grain)
        // The front bridge is deliberately smooth: no screws, bosses or recessed seats.
        paint.style = Paint.Style.FILL
    }
}
