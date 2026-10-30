package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact same-process Robolectric/native-Skia comparisons to the approved candidate (4058d21).
 * Frozen fixtures are identifier-only copies of the previous candidate dial and bridge sources.
 * These renderer tests do not exercise host touch, lifecycle, or Android device integration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Historical revision proof against the hash-checked frozen v0.8.3 renderer.
// Current production refinement and its allowed changes are checked by ReferenceReviewCandidateTest.
class CarbonBridgeNativeRegressionTest {
    private val epoch = Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private data class State(val name: String, val elapsed: Long, val running: Boolean, val motion: Boolean, val lume: Float)

    @Test fun engravingCleanupPreservesEveryPixelOutsideItsFixedUpperCap() {
        val zone = TimeZone.getDefault()
        val baseline = BaselineApprovedCarbonDialArt()
        val refined = Baseline083CarbonDialArt()
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
                    assertFalse("The localized engraving cleanup must actually be visible in the complete dial", expected.sameAs(actual))
                    val overlay=scaledFrozenOverlay(baseline,side)
                    val allowed=allowedEngravingCleanup(side)
                    try {
                        assertProtectedPixelsEqual(expected, actual, overlay, allowed, "${state.name}, $side px")
                    } finally {overlay.recycle();allowed.recycle()}
                } finally { expected.recycle(); actual.recycle() }
            }
        } finally { baseline.release(); refined.release(); TimeZone.setDefault(zone) }
    }

    @Test fun everyIsolatedHandAndRunningSecondsRasterMatchesTheFrozenBaseline() {
        val baseline = BaselineApprovedCarbonDialArt()
        val refined = Baseline083CarbonDialArt()
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

    @Test fun oldFrontScrewLocationsAreNowExactBareBridgePixelsInTheCompleteDial() {
        val previous=BaselineReviewCarbonDialArt(); val current=Baseline083CarbonDialArt()
        val zone=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); previous.onTimeChanged(); current.onTimeChanged()
            val formerScrews=listOf(420f to 300f,580f to 300f,330f to 586f,670f to 586f,402f to 755f,598f to 755f)
            for(side in listOf(720,1000)) {
                val expected=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
                val actual=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
                val old=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
                try {
                    val c=Canvas(expected);c.scale(side/1000f,side/1000f)
                    c.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
                    Baseline083CarbonBridgeArt().draw(c)
                    val bounds=RectF(0f,0f,side.toFloat(),side.toFloat())
                    current.draw(Canvas(actual),bounds,epoch,motion=false)
                    previous.draw(Canvas(old),bounds,epoch,motion=false)
                    for((sx,sy) in formerScrews) {
                        var samples=0;var removedPixels=0
                        for(y in 0 until side) for(x in 0 until side) {
                            val dx=500f+((x+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                            val dy=500f+((y+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                            if(insideCircle(dx,dy,sx,sy,2f)) {
                                samples++
                                val plain=expected.getPixel(x,y)
                                assertEquals("Bare support sample must be opaque",255,plain ushr 24)
                                assertEquals("No front screw pixels remain at $sx,$sy / $side px",plain,actual.getPixel(x,y))
                                if(old.getPixel(x,y)!=actual.getPixel(x,y)) removedPixels++
                            }
                        }
                        assertTrue("Every former screw must exercise actual pixels",samples>=4)
                        assertTrue("The frozen screw-bearing candidate must fail this bare-material check",removedPixels>0)
                    }
                } finally { expected.recycle();actual.recycle();old.recycle() }
            }
        } finally { previous.release();current.release();TimeZone.setDefault(zone) }
    }

    @Test fun hostAssemblyDateAndSecondsGeometryRemainExactlyAtTheBaseline() {
        assertEquals(BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.SECONDS_CENTER_Y, CarbonDialGeometry.SECONDS_CENTER_Y, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.SECONDS_DIAMETER, CarbonDialGeometry.SECONDS_DIAMETER, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_LEFT, CarbonDialGeometry.DATE_LEFT, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_TOP, CarbonDialGeometry.DATE_TOP, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_WIDTH, CarbonDialGeometry.DATE_WIDTH, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_HEIGHT, CarbonDialGeometry.DATE_HEIGHT, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_TEXT_LEFT, CarbonDialGeometry.DATE_TEXT_LEFT, 0f)
        assertEquals(BaselineApprovedCarbonDialGeometry.DATE_TEXT_WIDTH, CarbonDialGeometry.DATE_TEXT_WIDTH, 0f)
    }

    private fun assertProtectedPixelsEqual(expected: Bitmap, actual: Bitmap, overlay: Bitmap, allowed: Bitmap, label: String) {
        val side = expected.width
        val oldPixels = IntArray(side * side)
        val newPixels = IntArray(oldPixels.size)
        expected.getPixels(oldPixels, 0, side, 0, 0, side, side)
        actual.getPixels(newPixels, 0, side, 0, 0, side, side)
        val compared = IntArray(5)
        var outsideCleanupEnvelope=0
        val names = listOf("case/pushers", "date interior", "left counter interior", "right counter interior", "lower counter interior")
        for (y in 0 until side) for (x in 0 until side) {
            val designX = (x + .5f) * 1000f / side
            val designY = (y + .5f) * 1000f / side
            val assemblyX = 500f + (designX - 500f) / BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE
            val assemblyY = 500f + (designY - 500f) / BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE
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
            val at = y * side + x
            if((allowed.getPixel(x,y) ushr 24)==0) {
                outsideCleanupEnvelope++
                assertEquals("$label unauthorized change outside upper engraving cap at $x,$y",oldPixels[at],newPixels[at])
            }
            // At 240px the 256px cached overlay is downsampled. The outer date bevel's
            // translucent fringe legitimately mixes with the changed rear plate. Only actual
            // opaque date pixels are compared here; every overlay pixel remains independently
            // exact outside the two jewel patches (including its date bevel/antialiasing).
            if (protected >= 0 && (protected != 1 || (overlay.getPixel(x,y) ushr 24)==255)) {
                compared[protected]++
                if (oldPixels[at] != newPixels[at]) assertEquals("$label ${names[protected]} changed at $x,$y", oldPixels[at], newPixels[at])
            }
        }
        assertTrue("Over95% of the complete dial must remain outside the localized cleanup envelope",outsideCleanupEnvelope>side*side*.95f)
        assertTrue("Case mask must not become vacuous", compared[0] > side * side / 3)
        assertTrue("Date mask must exercise its actual interior", compared[1] > side * side / 1000)
        for (index in 2..4) assertTrue("${names[index]} mask must exercise substantial pixels", compared[index] > side * side / 100)
    }

    private fun scaledFrozenOverlay(art:BaselineApprovedCarbonDialArt,side:Int):Bitmap {
        val cached=art.javaClass.getDeclaredField("overlay").apply {isAccessible=true}.get(art) as Bitmap
        return Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(cached,null,RectF(0f,0f,side.toFloat(),side.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    /** Frozen eleven-oval cap only, never inferred from revised pixels or broad rear panels. */
    private fun allowedEngravingCleanup(side:Int):Bitmap = Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also { bitmap ->
        val c=Canvas(bitmap);c.scale(side/1000f,side/1000f)
        c.scale(BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE,BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        c.clipRect(0f,0f,1000f,390f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=.65f;strokeCap=Paint.Cap.BUTT
        }
        for(i in 0..10)c.drawOval(362f-i*3,329f-i*3,660f+i*3,648f+i*3,paint)
        // Fixed5design units cover antialiasing and the256→240 cache resampling fringe.
        // Dilation is purely geometric; exact colour comparisons still apply everywhere else.
        val radius=kotlin.math.ceil(5f*side/1000f*BaselineApprovedCarbonDialGeometry.ASSEMBLY_SCALE).toInt()
        val original=IntArray(side*side);bitmap.getPixels(original,0,side,0,0,side,side)
        val dilated=original.copyOf()
        for(y in 0 until side) for(x in 0 until side) if((original[y*side+x] ushr 24)!=0) {
            for(dy in -radius..radius) for(dx in -radius..radius) {
                val nx=x+dx;val ny=y+dy
                if(nx in 0 until side && ny in 0 until side && dx*dx+dy*dy<=radius*radius) dilated[ny*side+nx]=android.graphics.Color.WHITE
            }
        }
        bitmap.setPixels(dilated,0,side,0,0,side,side)
    }

    private fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean =
        (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
}
