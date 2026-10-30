package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The annotated request changes rear plates and exactly two jewels, not the approved front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Historical revision proof against the hash-checked frozen v0.8.3 renderer.
// Current production refinement and its allowed changes are checked by ReferenceReviewCandidateTest.
class CarbonBridgeAnnotatedRevisionTest {
    private val removed = listOf(275f to 621f, 737f to 586f)

    @Test fun approvedFrontBridgeSourceAndNativeRasterRemainExactlyA44fc7f() {
        val bytes=File("src/test/java/ai/nanosearch/launcher/clocks/Baseline083CarbonBridgeArt.kt").readText().replace("Baseline083", "").toByteArray(Charsets.UTF_8)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("The annotated rear-only request must not edit approved front support source",
            "bef33fa0f10c4e195bd270339ed9dad4d80d39d2e5f16aea28cfc07e656cf46e",hash)
        for(side in listOf(240,256,360,720,1000)) {
            val a=bitmap(side);val b=bitmap(side)
            try {
                BaselineAlignedCarbonBridgeArt().draw(canvas(a))
                Baseline083CarbonBridgeArt().draw(canvas(b))
                assertTrue("Every front support pixel, gap, bevel and shadow remains exact at $side px",a.sameAs(b))
            } finally {a.recycle();b.recycle()}
        }
    }

    @Test fun onlyTheTwoCircledJewelsDisappearFromAnOtherwiseExactFrontOverlay() {
        val previous=BaselineAlignedCarbonDialArt();val current=Baseline083CarbonDialArt()
        try {
            for(side in listOf(240,256,360,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    drawUpper(previous,canvas(a));drawUpper(current,canvas(b))
                    var protected=0;val removedCount=IntArray(2)
                    for(y in 0 until side) for(x in 0 until side) {
                        val dx=(x+.5f)*1000f/side;val dy=(y+.5f)*1000f/side
                        val marked=removed.indexOfFirst { (cx,cy) -> kotlin.math.abs(dx-cx)<12f && kotlin.math.abs(dy-cy)<14f }
                        if(marked<0) {
                            protected++
                            assertEquals("Unmarked overlay pixel changed at $x,$y/$side",a.getPixel(x,y),b.getPixel(x,y))
                        } else if(a.getPixel(x,y)!=b.getPixel(x,y)) removedCount[marked]++
                    }
                    assertTrue("Over 99% of the front layer remains exact",protected>side*side*.99f)
                    removedCount.forEachIndexed { index,count -> assertTrue("Circled jewel $index must actually disappear at $side",count>=4) }
                    // At high resolutions, inspect the real opaque purple core, not just a change count.
                    if(side>=720) for((cx,cy) in removed) {
                        var samples=0
                        for(y in 0 until side) for(x in 0 until side) {
                            val dx=(x+.5f)*1000f/side-cx;val dy=(y+.5f)*1000f/side-cy
                            if(dx*dx+dy*dy<=4f) {
                                samples++
                                assertEquals("Frozen marked jewel has an opaque purple core",0xff381347.toInt(),a.getPixel(x,y))
                                assertEquals("Removed jewel must not leave a recoloured or replacement front object",0,b.getPixel(x,y))
                            }
                        }
                        assertTrue("Every removed core exercises pixels",samples>=4)
                    }
                } finally {a.recycle();b.recycle()}
            }
        } finally {previous.release();current.release()}
    }

    @Test fun unmarkedTopAndAllThreeCounterJewelsRemainVisibleAndExact() {
        val previous=BaselineAlignedCarbonDialArt();val current=Baseline083CarbonDialArt()
        val a=bitmap(1000);val b=bitmap(1000)
        try {
            drawUpper(previous,canvas(a));drawUpper(current,canvas(b))
            val jewels=listOf(594f to 234f,369f to 438f,669f to 438f,(500f+19f*82f/95f) to (693f-27f*82f/95f))
            for((cx,cy) in jewels) {
                var purple=0;var samples=0
                for(y in 0 until 1000) for(x in 0 until 1000) {
                    if(kotlin.math.abs(x+.5f-cx)<8f && kotlin.math.abs(y+.5f-cy)<10f) {
                        samples++
                        val old=a.getPixel(x,y);val actual=b.getPixel(x,y)
                        assertEquals("Unmarked jewel changed at $cx,$cy",old,actual)
                        val r=(actual ushr 16) and 255;val g=(actual ushr 8) and 255;val blue=actual and 255
                        if((actual ushr 24)>200 && r>g*1.4f && blue>g*1.4f) purple++
                    }
                }
                assertTrue(samples>100)
                assertTrue("Unmarked jewel must still have actual purple pixels at $cx,$cy",purple>=6)
            }
        } finally {a.recycle();b.recycle();previous.release();current.release()}
    }

    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun canvas(bitmap:Bitmap)=Canvas(bitmap).apply {scale(bitmap.width/1000f,bitmap.height/1000f)}
    private fun drawUpper(art:Any,c:Canvas) {
        // Invoke the unchanged layer entry point, including real track/counter/date/jewel painters.
        art.javaClass.getDeclaredMethod("upper",Canvas::class.java).apply {isAccessible=true}.invoke(art,c)
    }
}
