package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.TimeZone
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockRenderingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun render(renderer: ClockFaceRenderer, epoch: Long, motion: Boolean = true, side: Int = 600): Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
        renderer.draw(Canvas(it), RectF(0f, 0f, side.toFloat(), side.toFloat()), epoch, motion)
    }
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun steelRendersAndMatchesItsExistingArtForTheSelectedBuild() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPersonalFont") ?: "false"
        assertTrue("Font expectation must select a concrete public or personal build", expected in listOf("false", "true"))
        val personalFont = context.assets.list("")!!.any { it.equals("Porscha911.ttf", true) }
        if (expected == "true") assertTrue("The local private-font build must retain its personal font", personalFont)
        else assertFalse("No personal font is available to this public-build regression", personalFont)
        val originalZone = TimeZone.getDefault()
        val renderer = ClockFaces.renderer(context, ClockFaces.STEEL_ID)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); renderer.onTimeChanged()
            val expected = DialArt(context).picture(600, 10, 9, 38f)
            val actual = render(renderer, at("2026-10-25T10:09:38Z"))
            assertTrue("Steel must retain the licensed fallback and existing dial/hands geometry", expected.sameAs(actual))
        } finally { renderer.release(); TimeZone.setDefault(originalZone) }
    }

    @Test fun carbonDateRollsAtMidnightAndRefreshesItsCachedTimezone() {
        val originalZone = TimeZone.getDefault()
        val renderer = ClockFaces.renderer(context, ClockFaces.CARBON_ID)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); renderer.onTimeChanged()
            fun date(bitmap: Bitmap) = Bitmap.createBitmap(bitmap, 328, 255, 54, 100)
            val before = date(render(renderer, at("2026-10-31T23:59:59Z"), false, 1000))
            val after = date(render(renderer, at("2026-11-01T00:00:00Z"), false, 1000))
            assertFalse("The vertical date must roll at local midnight", before.sameAs(after))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")); renderer.onTimeChanged()
            val shifted = date(render(renderer, at("2026-10-31T23:59:59Z"), false, 1000))
            assertTrue("Time-zone invalidation must immediately update the date", after.sameAs(shifted))
        } finally { renderer.release(); TimeZone.setDefault(originalZone) }
    }

    @Test fun sharedRenderersSweepOnlyInMotionModeAndRebuildAfterRelease() {
        val now = at("2026-10-25T10:09:38.100Z")
        for (definition in ClockFaces.all) {
            val renderer = ClockFaces.renderer(context, definition.id)
            try {
                assertFalse("${definition.id} must move within a second", render(renderer, now).sameAs(render(renderer, now + 500)))
                val still = render(renderer, now, false)
                assertTrue("${definition.id} reduced motion must be stable within a second", still.sameAs(render(renderer, now + 500, false)))
                renderer.release()
                assertTrue("${definition.id} must reconstruct the same owned cache after release", still.sameAs(render(renderer, now, false)))
            } finally { renderer.release() }
        }
    }

    @Test fun steelSecondsAreCoveredByOpaqueHourMinutePixels() {
        val originalZone = TimeZone.getDefault()
        val renderer = ClockFaces.renderer(context, ClockFaces.STEEL_ID)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); renderer.onTimeChanged()
            val art = DialArt(context)
            val seconds = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { art.drawSeconds(Canvas(it), 600, 0f) }
            val slow = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { art.drawHourMinute(Canvas(it), 600, 12, 0, 0f) }
            val full = render(renderer, at("2026-10-25T12:00:00Z"))
            var overlap = 0
            for (y in 60 until 260) for (x in 285 until 315) {
                if (Color.alpha(seconds.getPixel(x, y)) >= 250 && Color.alpha(slow.getPixel(x, y)) == 255) {
                    assertEquals("Seconds must be behind the solid hour/minute hand at$x,$y", slow.getPixel(x, y), full.getPixel(x, y))
                    overlap++
                }
            }
            assertTrue("The semantic layer test must exercise real overlapping pixels", overlap > 5)
        } finally { renderer.release(); TimeZone.setDefault(originalZone) }
    }

    @Test fun carbonLowerSecondsAreCoveredByTheDownwardMinuteHand() {
        val originalZone = TimeZone.getDefault()
        val renderer = ClockFaces.renderer(context, ClockFaces.CARBON_ID)
        val art = CarbonDialArt()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); renderer.onTimeChanged(); art.onTimeChanged()
            val time = at("2026-10-25T12:30:00Z")
            val bounds = RectF(0f, 0f, 1000f, 1000f)
            fun base(seconds: Boolean) = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { art.draw(Canvas(it), bounds, time, motion = false, hands = false, showRunningSeconds = seconds) }
            val secondsOn = base(true)
            val secondsOff = base(false)
            val minute = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also { art.drawHand(Canvas(it), bounds, 1, 180f) }
            val full = render(renderer, time, false, 1000)
            var overlap = 0
            for (y in 635 until 700) for (x in 490 until 511) {
                val a = secondsOn.getPixel(x, y); val b = secondsOff.getPixel(x, y)
                val delta = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
                if (delta > 32 && Color.alpha(minute.getPixel(x, y)) == 255) {
                    assertEquals("Lower seconds must be behind the minute hand at$x,$y", minute.getPixel(x, y), full.getPixel(x, y))
                    overlap++
                }
            }
            assertTrue("The semantic layer test must exercise the lower-seconds/minute intersection", overlap > 5)
        } finally { renderer.release(); art.release(); TimeZone.setDefault(originalZone) }
    }

    @Test fun carbonDecorativeGearsMoveWithoutAnyTimeHands() {
        val art = CarbonDialArt()
        val bounds = RectF(0f, 0f, 600f, 600f)
        val epoch = at("2026-10-25T10:09:38.100Z")
        fun mechanics(time: Long, motion: Boolean) = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also {
            art.draw(Canvas(it), bounds, time, motion = motion, hands = false, showRunningSeconds = false)
        }
        try {
            assertFalse("The decorative train itself must rotate, independently of all clock hands", mechanics(epoch, true).sameAs(mechanics(epoch + 500, true)))
            assertTrue("Reduced motion must park the decorative train", mechanics(epoch, false).sameAs(mechanics(epoch + 500, false)))
        } finally { art.release() }
    }

    @Test fun carbonCentralChronographPointerStaysBehindTheMinuteHand() {
        val originalZone = TimeZone.getDefault()
        val art = CarbonDialArt()
        val renderer = ClockFaces.renderer(context, ClockFaces.CARBON_ID)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); art.onTimeChanged(); renderer.onTimeChanged()
            val bounds = RectF(0f, 0f, 1000f, 1000f)
            val time = at("2026-10-25T00:00:00Z")
            // Compare complete compositions: alpha rounded to255 at an antialiased edge
            // does not imply that painting it over transparent and opaque targets is identical.
            val expected = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val expectedCanvas = Canvas(expected)
            art.draw(expectedCanvas, bounds, time, motion = false, hands = false)
            art.drawHand(expectedCanvas, bounds, 0, 0f)
            art.drawHand(expectedCanvas, bounds, 1, 0f)
            val full = render(renderer, time, false, 1000)
            assertTrue("The complete face must exactly match seconds below local-time hands", expected.sameAs(full))
            val wrongOrder = expected.copy(Bitmap.Config.ARGB_8888, true)
            art.drawHand(Canvas(wrongOrder), bounds, 2, 0f)
            var overlap = 0
            for (y in 250 until 460) for (x in 485 until 516) {
                if (expected.getPixel(x, y) != wrongOrder.getPixel(x, y)) overlap++
            }
            assertTrue("The test must reject a central pointer painted above the minute hand", overlap > 5)
        } finally { art.release(); renderer.release(); TimeZone.setDefault(originalZone) }
    }
}
