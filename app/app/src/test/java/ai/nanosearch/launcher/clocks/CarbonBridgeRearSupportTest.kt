package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/** Checks the actual repeated machining paths, not just configuration/list equality. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonBridgeRearSupportTest {
    private fun raster(path: Path, side: Int) = Region().apply {
        val scaled = Path(path).apply { transform(Matrix().apply { setScale(side / 1000f, side / 1000f) }) }
        setPath(scaled, Region(0, 0, side, side))
    }
    private fun sample(x: Float, y: Float, radius: Float, side: Int) = raster(Path().apply {
        addCircle(x, y, radius, Path.Direction.CW)
    }, side)
    private fun checkSample(panel: Region, patch: Region, supported: Boolean, label: String) {
        assertFalse("$label must exercise real pixels", patch.isEmpty)
        val result = Region(patch).apply { op(panel, if (supported) Region.Op.DIFFERENCE else Region.Op.INTERSECT) }
        assertTrue(label, result.isEmpty)
    }

    @Test fun allRearPanelsUseTheSameWheelCentresAngularDatumAndOpenSlotRhythm() {
        val expected = listOf(Triple(446f,270f,97f), Triple(624f,296f,67f), Triple(358f,632f,83f), Triple(655f,639f,77f))
        val supports = CarbonRearSupportGeometry.supports
        assertEquals(4, supports.size)
        assertEquals(22.5f, CarbonRearSupportGeometry.RIB_PHASE_DEGREES, 0f)
        assertEquals(45f, CarbonRearSupportGeometry.RIB_STEP_DEGREES, 0f)
        assertEquals(6f, CarbonRearSupportGeometry.RIB_WIDTH, 0f)
        assertEquals(.62f, CarbonRearSupportGeometry.INNER_RADIUS_SCALE, 0f)
        assertEquals(10f, CarbonRearSupportGeometry.OUTER_CLEARANCE, 0f)
        supports.forEachIndexed { index, support ->
            val (x,y,r) = expected[index]
            assertEquals(x,support.x,0f); assertEquals(y,support.y,0f); assertEquals(r,support.wheelRadius,0f)
            for (side in listOf(1000,2000)) {
                val material = raster(CarbonRearSupportGeometry.panelPath(support), side)
                val inner = r * .62f; val outer = r + 10f; val middle = (inner + outer) / 2
                fun at(radius: Float, degrees: Float): Region {
                    val angle = Math.toRadians(degrees.toDouble())
                    return sample(x + radius * cos(angle).toFloat(), y + radius * sin(angle).toFloat(), 1f, side)
                }
                for (rib in 0..7) {
                    val angle = 22.5f + rib * 45f
                    checkSample(material,at(middle,angle),true,"Panel $index rib $rib is aligned at $side px")
                    checkSample(material,at(middle,angle+22.5f),false,"Panel $index slot $rib stays open at $side px")
                    checkSample(material,at(inner,angle+22.5f),true,"Panel $index inner ring is concentric at $side px")
                    checkSample(material,at(outer,angle+22.5f),true,"Panel $index outer ring is concentric at $side px")
                }
            }
        }
    }

    @Test fun assembledRearPatternStaysOutOfOpeningsAndTheRemovedNeckGap() {
        val openings = Path().apply {
            addCircle(350f,465f,102f,Path.Direction.CW)
            addCircle(650f,465f,102f,Path.Direction.CW)
            addCircle(500f,693f,88f,Path.Direction.CW)
            addRoundRect(333f,263f,391f,364f,7f,7f,Path.Direction.CW)
            addCircle(500f,500f,7f,Path.Direction.CW)
            addRect(484f,564f,516f,595f,Path.Direction.CW)
        }
        for (side in listOf(240,360,720,1000)) {
            val material = raster(CarbonRearSupportGeometry.frame(),side)
            assertFalse(material.isEmpty)
            assertTrue("Rear support must not substitute for the removed front neck", Region(material).apply {
                op(raster(openings,side),Region.Op.INTERSECT)
            }.isEmpty)
            assertTrue("Rear support stays inside the unchanged dial", Region(material).apply {
                op(raster(CarbonPusherGeometry.innerDialPath(),side),Region.Op.DIFFERENCE)
            }.isEmpty)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun rearPaintingIsDeterministicAndCannotLeakCanvasState() {
        fun draw(art: CarbonRearSupportArt, side: Int): Bitmap = Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it); c.scale(side/1000f,side/1000f); c.clipRect(200f,210f,800f,800f)
            val matrix=Matrix().also(c::getMatrix); val clip=Rect().also { c.getClipBounds(it) }; val count=c.saveCount
            art.draw(c)
            assertEquals(matrix,Matrix().also(c::getMatrix)); assertEquals(clip,Rect().also { c.getClipBounds(it) }); assertEquals(count,c.saveCount)
        }
        val reused=CarbonRearSupportArt()
        for(side in listOf(240,360,720)) {
            val a=draw(reused,side); val b=draw(reused,side); val c=draw(CarbonRearSupportArt(),side)
            try { assertTrue(a.sameAs(b)); assertTrue(a.sameAs(c)) } finally { a.recycle();b.recycle();c.recycle() }
        }
    }
}
