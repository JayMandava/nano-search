package ai.nanosearch.launcher.clocks

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.max
import kotlin.math.sqrt

/** A continuous local inner lip; the register stays circular underneath it. */
internal object CarbonSideBezelSeat {
    fun geometry():Path {
        // Sample the actual case contour's inward nine-unit offset, not a rectangle.
        val edge=Path().apply {moveTo(327f,240f);cubicTo(276f,290f,234f,405f,240f,500f);cubicTo(234f,600f,276f,720f,327f,770f)}
        val measure=PathMeasure(edge,false);val xy=FloatArray(2);val t=FloatArray(2)
        val samples=(0..1600).map {i->
            measure.getPosTan(measure.length*i/1600f,xy,t)
            (xy[1]-t[0]*9f) to (xy[0]+t[1]*9f)
        }
        fun base(y:Float):Float {
            val i=samples.indexOfFirst {it.first>=y}.coerceAtLeast(1)
            val (a,x)=samples[i-1];val (b,z)=samples[i]
            return x+(z-x)*(y-a)/(b-a)
        }
        fun smooth(v:Float)=v.coerceIn(0f,1f).let {it*it*(3f-2f*it)}
        val boundary=(385..550).map {row->
            val y=row.toFloat();val x=base(y)
            val weight=smooth((y-385f)/25f)*smooth((545f-y)/30f)
            // Two units of overlap past the outer collar aperture (r103),
            // while remaining clear of the complete front black seat (r100).
            val collar=350f-sqrt(max(0f,101f*101f-(y-465f)*(y-465f)))
            Triple(y,x,x+max(0f,collar-x)*weight)
        }
        val left=Path().apply {
            moveTo(boundary.first().third,boundary.first().first)
            for((y,_,x) in boundary.drop(1))lineTo(x,y)
            for((y,x,_) in boundary.asReversed())lineTo(x-1f,y)
            close()
        }
        return Path(left).apply {check(op(Path(left).apply {transform(Matrix().apply {setScale(-1f,1f,500f,500f)})},Path.Op.UNION))}
    }
}
