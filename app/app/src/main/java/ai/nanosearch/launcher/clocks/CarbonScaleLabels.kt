package ai.nanosearch.launcher.clocks

import android.graphics.*
import kotlin.math.atan2

/** Labels belong wholly to the dark scale channel, including rotated ink bounds. */
internal object CarbonScaleLabels {
    data class Label(val value:String,val ink:Path,val box:Path,val x:Float,val y:Float,val angle:Float,val size:Float)
    private val originals=listOf(Triple("65",307f,219f),Triple("70",253f,317f),Triple("80",214f,440f),Triple("90",214f,597f),Triple("105",287f,773f),Triple("120",500f,820f),Triple("180",713f,773f),Triple("240",786f,597f),Triple("360",786f,440f),Triple("720",693f,219f))
    fun safeChannel():Path {
        val outer=CarbonPusherGeometry.innerDialPath().apply {transform(Matrix().apply {setScale(1.12f,1.09f,500f,500f)})}
        val rim=Path();Paint().apply {style=Paint.Style.STROKE;strokeWidth=8.5f}.getFillPath(outer,rim)
        check(outer.op(rim,Path.Op.DIFFERENCE))
        val blue=Path();Paint().apply {style=Paint.Style.FILL_AND_STROKE;strokeWidth=15f}.getFillPath(CarbonPusherGeometry.innerDialPath(),blue)
        check(outer.op(blue,Path.Op.DIFFERENCE));return outer
    }
    val labels:List<Label> by lazy {
        val centre=CarbonPusherGeometry.innerDialPath().apply {transform(Matrix().apply {setScale(1.06f,1.045f,500f,500f)})}
        val measure=PathMeasure(centre,true);val xy=FloatArray(2);val tangent=FloatArray(2)
        val channel=safeChannel()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("sans-serif-condensed",Typeface.NORMAL)}
        originals.map {(value,oldX,oldY)->
            var distance=0f;var best=Float.MAX_VALUE
            for(i in 0 until 4096) {
                val d=measure.length*i/4096f;measure.getPosTan(d,xy,tangent)
                val error=(xy[0]-oldX)*(xy[0]-oldX)+(xy[1]-oldY)*(xy[1]-oldY)
                if(error<best){best=error;distance=d}
            }
            measure.getPosTan(distance,xy,tangent)
            var angle=Math.toDegrees(atan2(tangent[1],tangent[0]).toDouble()).toFloat()
            if(angle>90f)angle-=180f;if(angle< -90f)angle+=180f
            var selected:Label?=null
            for(size in listOf(12f,11.5f,11f)) {
                if(selected!=null)break
                paint.textSize=size
                val ink=Path();paint.getTextPath(value,0,value.length,0f,0f,ink)
                val bounds=RectF();ink.computeBounds(bounds,true)
                for(offset in listOf(0f,1f,-1f,2f,-2f,3f,-3f)) {
                    val x=xy[0]-tangent[1]*offset;val y=xy[1]+tangent[0]*offset
                    val m=Matrix().apply {setTranslate(-bounds.centerX(),-bounds.centerY());postRotate(angle);postTranslate(x,y)}
                    val glyph=Path(ink).apply {transform(m)}
                    val box=Path().apply {addRect(bounds,Path.Direction.CW);transform(m)}
                    val outside=Path(box);check(outside.op(channel,Path.Op.DIFFERENCE))
                    if(outside.isEmpty){selected=Label(value,glyph,box,x,y,angle,size);break}
                }
            }
            checkNotNull(selected){"Scale label $value cannot fit the safe channel"}
        }
    }
    fun draw(canvas:Canvas,color:Int) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.FILL}
        for(label in labels)canvas.drawPath(label.ink,paint)
    }
}
