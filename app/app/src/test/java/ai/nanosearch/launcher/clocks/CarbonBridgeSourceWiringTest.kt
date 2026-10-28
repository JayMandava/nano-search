package ai.nanosearch.launcher.clocks

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring complements real raster tests; it is not a substitute for rendered pixels. */
class CarbonBridgeSourceWiringTest {
    private fun source(name: String) = File("src/main/java/ai/nanosearch/launcher/clocks/$name.kt").readText()

    @Test fun frontBridgeHasNoFastenerOrSeatDrawingButUnrelatedScrewsRemain() {
        val dial=source("CarbonDialArt")
        val upper=dial.substringAfter("private fun upper(c: Canvas)").substringBefore("private fun track(c: Canvas)")
        assertTrue("Source audit must resolve the actual upper layer",upper.contains("bridgeArt.draw(c)"))
        assertFalse("Front bridge must not call the screw painter",Regex("\\bscrew\\s*\\(").containsMatchIn(upper))
        val front=source("CarbonBridgeArt")
        val obsolete=Regex("""\b(?:val|var)\s+bosses\b|\bdata\s+class\s+Boss\b|\.bosses\b|\bscrewRadius\b""")
        assertTrue("Identifier audit must detect real declarations",obsolete.containsMatchIn("val bosses = listOf()"))
        assertTrue("Identifier audit must detect real usages",obsolete.containsMatchIn("geometry.bosses"))
        assertFalse("No obsolete boss declarations/usages remain",obsolete.containsMatchIn(front))
        assertFalse("No circular screw-seat painting remains",front.contains("drawCircle("))
        assertTrue("Case fasteners remain intentionally present",dial.contains("screw(c, x, y, 18f)"))
        assertTrue("Rear plate screws remain intentionally present",dial.contains("screw(c,x,y,7f)"))
        assertTrue("Counter movement screw remains intentionally present",dial.contains("screw(c,-22f,-29f,8f)"))
    }

    @Test fun alignedRearSupportIsWiredBehindAnimatedWheelsNotOnTheFrontOverlay() {
        val dial=source("CarbonDialArt")
        val base=dial.substringAfter("private fun underlay(c: Canvas)").substringBefore("private fun upper(c: Canvas)")
        val upper=dial.substringAfter("private fun upper(c: Canvas)").substringBefore("private fun track(c: Canvas)")
        assertTrue(base.contains("bridgeArt.drawRear(c)"))
        assertFalse(upper.contains("drawRear"))
        assertEquals("Rear support must be installed exactly once",1,Regex("bridgeArt\\.drawRear\\(c\\)").findAll(dial).count())
        val draw=dial.substringAfter("    fun draw(").substringBefore("    fun drawHand(")
        val baseIndex=draw.indexOf("drawBitmap(base!!")
        val wheels=draw.indexOf("gear(canvas, 446f")
        val frontIndex=draw.indexOf("drawBitmap(overlay!!")
        assertTrue("Actual draw order must be base, animated train, then front bridge",baseIndex>=0 && wheels>baseIndex && frontIndex>wheels)
    }
}
