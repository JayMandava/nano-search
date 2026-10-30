package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/** Shared clockwise-from-up datum for every sector, label and pointer. */
internal object CarbonBatteryGeometry {
    const val PIVOT_X = 704f
    const val PIVOT_Y = 712f
    const val SCALE_RADIUS = 50f
    const val LABEL_RADIUS = 65f
    const val POINTER_LENGTH = 41f
    const val LABEL_BASELINE_DATUM = -65f
    fun labelTilt(angle: Float) = ((angle-LABEL_BASELINE_DATUM)*.35f).coerceIn(-14f,14f)
    fun point(angle: Float, radius: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angle.toDouble())
        return PIVOT_X + sin(radians).toFloat() * radius to PIVOT_Y - cos(radians).toFloat() * radius
    }
}

/** Small sector over the movement, with no per-frame path or font construction. */
internal class CarbonBatteryArt {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD) }
    private val circle=RectF(CarbonBatteryGeometry.PIVOT_X-CarbonBatteryGeometry.SCALE_RADIUS,CarbonBatteryGeometry.PIVOT_Y-CarbonBatteryGeometry.SCALE_RADIUS,CarbonBatteryGeometry.PIVOT_X+CarbonBatteryGeometry.SCALE_RADIUS,CarbonBatteryGeometry.PIVOT_Y+CarbonBatteryGeometry.SCALE_RADIUS)
    private val arrow=Path().apply {
        moveTo(0f,-41f);lineTo(5f,-30f);lineTo(1.8f,-32f)
        lineTo(1.8f,6f);lineTo(-1.8f,6f);lineTo(-1.8f,-32f);lineTo(-5f,-30f);close()
    }
    private fun color(band: BatteryBand) = when(band) {
        BatteryBand.HIGH -> 0xffdde328.toInt()
        BatteryBand.MEDIUM -> 0xffe6eae8.toInt()
        BatteryBand.LOW -> 0xff35b9db.toInt()
    }
    fun drawScale(canvas: Canvas) {
        val save=canvas.save()
        paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND
        paint.strokeWidth=14f;paint.color=0xdd080c10.toInt()
        canvas.drawArc(circle,-207f,99f,false,paint)
        for(band in BatteryBand.entries) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2.8f;paint.color=color(band)
            canvas.drawArc(circle,band.angle-101f,22f,false,paint)
            // End ticks mark the sector boundaries; no crossing mark at its centre.
            paint.strokeWidth=1.7f
            for(end in listOf(band.angle-11f,band.angle+11f)) {
                val (x1,y1)=CarbonBatteryGeometry.point(end,48f)
                val (x2,y2)=CarbonBatteryGeometry.point(end,53f)
                canvas.drawLine(x1,y1,x2,y2,paint)
            }
            val (x,y)=CarbonBatteryGeometry.point(band.angle,CarbonBatteryGeometry.LABEL_RADIUS)
            val label=when(band) { BatteryBand.HIGH -> "H";BatteryBand.MEDIUM -> "M";BatteryBand.LOW -> "L" }
            val labelSave=canvas.save();canvas.translate(x,y)
            canvas.rotate(CarbonBatteryGeometry.labelTilt(band.angle))
            paint.textSize=21f;paint.textAlign=Paint.Align.CENTER
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3.6f;paint.color=0xff080c10.toInt()
            canvas.drawText(label,0f,7f,paint)
            paint.style=Paint.Style.FILL;paint.color=color(band)
            canvas.drawText(label,0f,7f,paint)
            canvas.restoreToCount(labelSave)
        }
        paint.style=Paint.Style.FILL;paint.color=0xff080c10.toInt()
        canvas.drawCircle(CarbonBatteryGeometry.PIVOT_X,CarbonBatteryGeometry.PIVOT_Y,5f,paint)
        canvas.restoreToCount(save)
    }
    fun drawPointer(canvas: Canvas, band: BatteryBand) {
        val save=canvas.save()
        canvas.translate(CarbonBatteryGeometry.PIVOT_X,CarbonBatteryGeometry.PIVOT_Y);canvas.rotate(band.angle)
        paint.style=Paint.Style.FILL;paint.color=0xffe6eae8.toInt();canvas.drawPath(arrow,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.75f;paint.color=0xff7f898a.toInt();canvas.drawPath(arrow,paint)
        paint.style=Paint.Style.FILL;paint.color=0xffff731d.toInt();canvas.drawRoundRect(-1.3f,-34f,1.3f,-29f,.7f,.7f,paint)
        paint.color=0xff080c10.toInt();canvas.drawCircle(0f,0f,3.5f,paint)
        canvas.restoreToCount(save)
    }
}
