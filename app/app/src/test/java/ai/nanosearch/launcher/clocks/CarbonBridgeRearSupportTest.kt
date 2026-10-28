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

    @Test fun allFourRearFramesHaveMirroredWallsCommonWebWidthAndMatchingChamfers() {
        val panels=CarbonRearSupportGeometry.panels
        assertEquals(listOf(false,false,true,true),panels.sortedBy { it.mirrorX }.map { it.mirrorX })
        assertEquals(listOf(210f to 398f,210f to 398f,552f to 800f,552f to 800f),panels.map { it.top to it.bottom })
        assertEquals(listOf(false,true,false,true),panels.map { it.mirrorX })
        assertEquals(250f,CarbonRearSupportGeometry.LEFT,0f)
        assertEquals(445f,CarbonRearSupportGeometry.RIGHT,0f)
        assertEquals(28f,CarbonRearSupportGeometry.OUTER_CORNER,0f)
        assertEquals(14f,CarbonRearSupportGeometry.WEB_WIDTH,0f)
        assertEquals(28f+14f*(kotlin.math.sqrt(2f)-2f),CarbonRearSupportGeometry.INNER_CORNER,.0001f)
        for(side in listOf(1000,2000)) {
            for(index in listOf(0,2)) {
                val left=panels[index];val right=panels[index+1]
                val material=raster(CarbonRearSupportGeometry.panelPath(left),side)
                val mirrored=Path(CarbonRearSupportGeometry.panelPath(right)).apply {
                    transform(Matrix().apply {setScale(-1f,1f,500f,500f)})
                }
                assertTrue("Both actual panel paths must be exact mirror partners",Region(material).apply {
                    op(raster(mirrored,side),Region.Op.XOR)
                }.isEmpty)
                val top=left.top;val bottom=left.bottom
                val solid=listOf(257f to (top+60f),438f to (top+60f),347f to (top+7f),347f to (bottom-7f),267f to (top+17f),267f to (bottom-17f))
                val open=listOf(280f to (top+60f),420f to (top+60f),347f to (top+25f),347f to (bottom-25f),251f to (top+1f),251f to (bottom-1f))
                solid.forEach { (x,y) -> checkSample(material,sample(x,y,1f,side),true,"Common wall/chamfer at $x,$y") }
                open.forEach { (x,y) -> checkSample(material,sample(x,y,1f,side),false,"Window/corner cut stays open at $x,$y") }
                // Probe the real 45-degree chamfer along its inward unit normal. Its web
                // must be the same 14 units as straight walls, not 14/sqrt(2).
                for(distance in listOf(-2f,2f,7f,12f,16f)) {
                    val offset=distance/kotlin.math.sqrt(2f)
                    checkSample(material,sample(264f+offset,top+14f+offset,.6f,side),
                        distance>0f && distance<14f,"True diagonal normal web at distance $distance/$side")
                }
                // The long straight walls retain precisely the shared 14-unit web, not arbitrary thickness.
                for(x in 247..448) {
                    val expected=x in 250 until 264 || x in 431 until 445
                    assertEquals("Exact shared wall width at x=$x, side=$side",expected,
                        material.contains(((x+.5f)*side/1000f).toInt(),((top+60.5f)*side/1000f).toInt()))
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
