package ai.nanosearch.launcher.clocks

import android.graphics.*

/** Local replacement for the relieved bottom of the independent six-o'clock collar. */
internal object CarbonLowerMetalCollarGeometry {
    // The complete circular ring continues behind the foreground bezel. Its
    // visible boundary comes from case-aligned occlusion, never a flat cut.
    fun frame():Path {
        val outer=Path().apply {
            addCircle(500f,693f,97f,Path.Direction.CW)
        }
        val inner=Path().apply {
            addCircle(500f,693f,89f,Path.Direction.CW)
        }
        check(outer.op(inner,Path.Op.DIFFERENCE))
        return outer
    }

    fun oldLowerArcMask():Path=Path().apply {
        addCircle(500f,693f,101f,Path.Direction.CW)
        check(op(Path().apply {addRect(390f,754f,610f,825f,Path.Direction.CW)},Path.Op.INTERSECT))
    }

    fun replacement():Path=frame().apply {
        check(op(Path().apply {addRect(400f,750f,600f,800f,Path.Direction.CW)},Path.Op.INTERSECT))
        check(op(CarbonPusherGeometry.innerDialPath(),Path.Op.INTERSECT))
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
        // Called inside the dial clip, before the bezel and counters. The
        // complete lower circle is hidden wherever it passes behind the case.
        canvas.clipRect(400f,750f,600f,800f)
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
