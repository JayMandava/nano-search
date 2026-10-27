package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact same-process Robolectric/native-Skia comparisons to Nano 0.8.1 (d515470).
 * Frozen fixtures are identifier-only copies of the public dial and bridge sources.
 * These renderer tests do not exercise host touch, lifecycle, or Android device integration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonBridgeNativeRegressionTest {
    private val epoch = Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private data class State(val name: String, val elapsed: Long, val running: Boolean, val motion: Boolean, val lume: Float)

    @Test fun bridgeOnlyRefinementPreservesCasePushersDateAndCounterInteriorsExactly() {
        val zone = TimeZone.getDefault()
        val baseline = Baseline081CarbonDialArt()
        val refined = CarbonDialArt()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            baseline.onTimeChanged(); refined.onTimeChanged()
            val states = listOf(
                State("parked", 0L, false, false, 0f),
                State("running", 12_345L, true, true, 0f),
                State("lume", 0L, false, false, 1f),
            )
            for (side in listOf(240, 360, 720, 1000)) for (state in states) {
                val expected = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                try {
                    val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
                    baseline.draw(Canvas(expected), bounds, epoch, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                    refined.draw(Canvas(actual), bounds, epoch, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                    assertFalse("The new bridge must actually be wired into the complete dial", expected.sameAs(actual))
                    assertProtectedPixelsEqual(expected, actual, "${state.name}, $side px")
                } finally { expected.recycle(); actual.recycle() }
            }
        } finally { baseline.release(); refined.release(); TimeZone.setDefault(zone) }
    }

    @Test fun everyIsolatedHandAndRunningSecondsRasterMatchesTheFrozenBaseline() {
        val baseline = Baseline081CarbonDialArt()
        val refined = CarbonDialArt()
        try {
            for (side in listOf(240, 720)) for (kind in 0..2) for (angle in listOf(0f, 90f, 180f, 315.25f)) {
                val expected = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                try {
                    val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
                    baseline.drawHand(Canvas(expected), bounds, kind, angle)
                    refined.drawHand(Canvas(actual), bounds, kind, angle)
                    assertTrue("Bridge refinement must not change hand $kind at $angle degrees/$side px", expected.sameAs(actual))
                } finally { expected.recycle(); actual.recycle() }
            }
            for (side in listOf(164, 328)) {
                val expected = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                try {
                    val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
                    baseline.drawSecondsHand(Canvas(expected), bounds)
                    refined.drawSecondsHand(Canvas(actual), bounds)
                    assertTrue("Bridge refinement must preserve the separate native seconds-hand raster", expected.sameAs(actual))
                } finally { expected.recycle(); actual.recycle() }
            }
        } finally { baseline.release(); refined.release() }
    }

    @Test fun hostAssemblyDateAndSecondsGeometryRemainExactlyAtTheBaseline() {
        assertEquals(Baseline081CarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 0f)
        assertEquals(Baseline081CarbonDialGeometry.SECONDS_CENTER_Y, CarbonDialGeometry.SECONDS_CENTER_Y, 0f)
        assertEquals(Baseline081CarbonDialGeometry.SECONDS_DIAMETER, CarbonDialGeometry.SECONDS_DIAMETER, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_LEFT, CarbonDialGeometry.DATE_LEFT, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_TOP, CarbonDialGeometry.DATE_TOP, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_WIDTH, CarbonDialGeometry.DATE_WIDTH, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_HEIGHT, CarbonDialGeometry.DATE_HEIGHT, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_TEXT_LEFT, CarbonDialGeometry.DATE_TEXT_LEFT, 0f)
        assertEquals(Baseline081CarbonDialGeometry.DATE_TEXT_WIDTH, CarbonDialGeometry.DATE_TEXT_WIDTH, 0f)
    }

    private fun assertProtectedPixelsEqual(expected: Bitmap, actual: Bitmap, label: String) {
        val side = expected.width
        val oldPixels = IntArray(side * side)
        val newPixels = IntArray(oldPixels.size)
        expected.getPixels(oldPixels, 0, side, 0, 0, side, side)
        actual.getPixels(newPixels, 0, side, 0, 0, side, side)
        val compared = IntArray(5)
        val names = listOf("case/pushers", "date interior", "left counter interior", "right counter interior", "lower counter interior")
        for (y in 0 until side) for (x in 0 until side) {
            val designX = (x + .5f) * 1000f / side
            val designY = (y + .5f) * 1000f / side
            val assemblyX = 500f + (designX - 500f) / Baseline081CarbonDialGeometry.ASSEMBLY_SCALE
            val assemblyY = 500f + (designY - 500f) / Baseline081CarbonDialGeometry.ASSEMBLY_SCALE
            // These masks come from the frozen case/counter/date geometry, never the new frame.
            // Excluding aperture-edge antialias pixels is a geometric mask, not a color tolerance:
            // every included pixel must match exactly, including all opaque counter interiors.
            val protected = when {
                designX < 220f || designX >= 780f || designY < 200f || designY >= 820f -> 0
                assemblyX >= 339f && assemblyX < 385f && assemblyY >= 269f && assemblyY < 358f -> 1
                insideCircle(assemblyX, assemblyY, 350f, 465f, 94f) -> 2
                insideCircle(assemblyX, assemblyY, 650f, 465f, 94f) -> 3
                insideCircle(assemblyX, assemblyY, 500f, 693f, 81f) -> 4
                else -> -1
            }
            if (protected >= 0) {
                compared[protected]++
                val at = y * side + x
                if (oldPixels[at] != newPixels[at]) assertEquals("$label ${names[protected]} changed at $x,$y", oldPixels[at], newPixels[at])
            }
        }
        assertTrue("Case mask must not become vacuous", compared[0] > side * side / 3)
        assertTrue("Date mask must exercise its actual interior", compared[1] > side * side / 1000)
        for (index in 2..4) assertTrue("${names[index]} mask must exercise substantial pixels", compared[index] > side * side / 100)
    }

    private fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean =
        (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
}
