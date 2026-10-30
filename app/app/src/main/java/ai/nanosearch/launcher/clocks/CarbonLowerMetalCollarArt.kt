package ai.nanosearch.launcher.clocks

import android.graphics.*

/** Local replacement for the relieved bottom of the independent six-o'clock collar. */
internal object CarbonLowerMetalCollarGeometry {
    // Original circular shoulders and tangents remain unchanged. The lower
    // quadrant eases into a shallow curved seat, never a flat rectangular foot.
    fun frame():Path {
        val outer=Path().apply {
            arcTo(RectF(403f,596f,597f,790f),135f,270f,true)
            cubicTo(550.089f,780.089f,523f,780.6f,500f,780.6f)
            cubicTo(477f,780.6f,449.911f,780.089f,431.411f,761.589f)
            close()
        }
        val inner=Path().apply {
            arcTo(RectF(411f,604f,589f,782f),135f,270f,true)
            cubicTo(548.083f,770.783f,522f,776.8f,500f,776.8f)
            cubicTo(478f,776.8f,451.917f,770.783f,437.067f,755.933f)
            close()
        }
        check(outer.op(inner,Path.Op.DIFFERENCE))
        return outer
    }

    fun oldLowerArcMask():Path=Path().apply {
        addCircle(500f,693f,101f,Path.Direction.CW)
        check(op(Path().apply {addRect(390f,754f,610f,825f,Path.Direction.CW)},Path.Op.INTERSECT))
    }

    fun replacement():Path=frame().apply {
        check(op(Path().apply {addRect(400f,750f,600f,786f,Path.Direction.CW)},Path.Op.INTERSECT))
    }

    /** Exact front-material composition used to check connection and clearance. */
    fun composedFrame():Path=CarbonBridgeGeometry.frame().apply {
        check(op(oldLowerArcMask(),Path.Op.DIFFERENCE))
        check(op(replacement(),Path.Op.UNION))
    }
}

/** Cached paths only; this painter runs when Carbon's static overlay is rebuilt. */
internal class CarbonLowerMetalCollarArt {
    private val frame=CarbonLowerMetalCollarGeometry.frame()
    private val oldArc=CarbonLowerMetalCollarGeometry.oldLowerArcMask()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val body=LinearGradient(320f,235f,690f,785f,intArrayOf(
        0xff484b49.toInt(),0xff303332.toInt(),0xff454947.toInt(),0xff292d2c.toInt()),null,Shader.TileMode.CLAMP)
    private val bevel=LinearGradient(300f,225f,720f,780f,intArrayOf(
        0xffd1d5cc.toInt(),0xff909b96.toInt(),0xffc5ccc1.toInt(),0xff747f79.toInt()),null,Shader.TileMode.CLAMP)

    fun clipOldLowerArc(canvas:Canvas)=canvas.clipOutPath(oldArc)

    fun draw(canvas:Canvas) {
        val local=canvas.save()
        // Four units overlap the unchanged upper arc so the join cannot expose
        // a clipping seam. The new bottom stays inside the blue track's edge.
        canvas.clipRect(400f,750f,600f,786f)
        val shadow=canvas.save();canvas.translate(1.5f,2.5f)
        paint.color=0xb8000000.toInt();paint.shader=null;paint.style=Paint.Style.FILL
        canvas.drawPath(frame,paint);canvas.restoreToCount(shadow)
        paint.color=Color.WHITE;paint.shader=body;paint.style=Paint.Style.FILL
        canvas.drawPath(frame,paint)
        val edge=canvas.save();canvas.clipPath(frame)
        paint.shader=bevel;paint.style=Paint.Style.STROKE;paint.strokeWidth=5.6f;paint.strokeJoin=Paint.Join.ROUND
        canvas.drawPath(frame,paint)
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=.35f
        for(i in 0..210) {
            paint.color=if(i%4==0)0x185e6862 else 0x10202a25
            val y=230f+i*2.7f;canvas.drawLine(235f,y,770f,y-2f,paint)
        }
        canvas.restoreToCount(edge);canvas.restoreToCount(local)
    }
}
