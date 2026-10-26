package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarbonLumeRenderingTest {
    @Test fun fixedTimeGlowLightsBothHandInsertsAndIndicesWithoutChangingDateCaseOrCounters() {
        val original = TimeZone.getDefault()
        val renderer = CarbonClockFaceRenderer()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); renderer.onTimeChanged()
            val time = Instant.parse("2026-10-25T12:30:00Z").toEpochMilli()
            fun draw(alpha: Float) = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also {
                renderer.drawChronograph(Canvas(it), RectF(0f, 0f, 1000f, 1000f), time, false, 90500, true, alpha)
            }
            val dark = draw(0f)
            val lit = draw(1f)
            fun transformed(x: Float, y: Float) = PointF(500f + (x - 500f) * CarbonDialGeometry.ASSEMBLY_SCALE, 500f + (y - 500f) * CarbonDialGeometry.ASSEMBLY_SCALE)
            fun handPoint(y: Float, degrees: Double): PointF {
                val radians = Math.toRadians(degrees)
                return transformed(500f - (y - 500f) * sin(radians).toFloat(), 500f + (y - 500f) * cos(radians).toFloat())
            }
            fun changedNear(point: PointF): Int {
                var changed = 0
                for (y in point.y.toInt() - 6..point.y.toInt() + 6) for (x in point.x.toInt() - 6..point.x.toInt() + 6) {
                    val a = dark.getPixel(x, y); val b = lit.getPixel(x, y)
                    if (maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))) > 20) changed++
                }
                return changed
            }
            assertTrue("Hour-hand luminous insert must actually light", changedNear(handPoint(330f, 15.0)) > 20)
            assertTrue("Minute-hand luminous insert must actually light", changedNear(handPoint(290f, 180.0)) > 20)
            assertTrue("Applied twelve-o'clock index must actually light", changedNear(transformed(500f, 235f)) > 20)
            fun crop(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) = Bitmap.createBitmap(bitmap, x, y, width, height)
            assertTrue("Crown light must not repaint the case", crop(dark, 170, 80, 70, 60).sameAs(crop(lit, 170, 80, 70, 60)))
            assertTrue("Crown light must not alter the date", crop(dark, 339, 271, 34, 70).sameAs(crop(lit, 339, 271, 34, 70)))
            assertTrue("Crown light must not advance/reset chronograph counters", crop(dark, 310, 425, 80, 80).sameAs(crop(lit, 310, 425, 80, 80)))
            assertTrue("Turning the light off must restore exactly the fixed-time dial", dark.sameAs(draw(0f)))
        } finally { renderer.release(); TimeZone.setDefault(original) }
    }
}
