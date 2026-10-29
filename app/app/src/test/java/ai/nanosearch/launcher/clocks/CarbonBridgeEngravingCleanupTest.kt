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

/** Removes only the floating upper engraved strokes; the approved design is otherwise locked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Historical revision proof against the hash-checked frozen v0.8.3 renderer.
// Current production refinement and its allowed changes are checked by CarbonRefinement084Test.
class CarbonBridgeEngravingCleanupTest {
    @Test fun entireApprovedFrontOverlayAndBothHistoricalSupportSourcesRemainExact() {
        val hashes=mapOf(
            "CarbonBridgeArt" to "bef33fa0f10c4e195bd270339ed9dad4d80d39d2e5f16aea28cfc07e656cf46e",
            "CarbonRearSupportArt" to "a72ea5b0dc1023c7752bc0161bb5b96740485ec0860be9a1add44173f4d4445d",
        )
        for((name,expected) in hashes) {
            val bytes=File("src/test/java/ai/nanosearch/launcher/clocks/Baseline083$name.kt").readText().replace("Baseline083", "").toByteArray(Charsets.UTF_8)
            assertEquals("Approved$name is outside cleanup scope",expected,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)})
        }
        val previous=BaselineApprovedCarbonDialArt();val current=Baseline083CarbonDialArt()
        try {
            for(side in listOf(240,256,360,720,1000)) {
                val a=drawLayer(previous,"upper",side);val b=drawLayer(current,"upper",side)
                try {assertTrue("Entire approved front overlay must be exact at$side",a.sameAs(b))}
                finally {a.recycle();b.recycle()}
            }
        } finally {previous.release();current.release()}
    }

    @Test fun realUpperEngravedStrokesDisappearWhileAllLowerEngravingRemainsExact() {
        val previous=BaselineApprovedCarbonDialArt();val current=Baseline083CarbonDialArt()
        try {
            for(side in listOf(720,1000)) {
                val a=drawLayer(previous,"underlay",side);val b=drawLayer(current,"underlay",side)
                val oldOverlay=drawLayer(previous,"upper",side);val newOverlay=drawLayer(current,"upper",side)
                try {
                    var cleared=0;var lowerEngraving=0;var lowerChecked=0
                    for(y in 0 until side) for(x in 0 until side) {
                        val dx=500f+((x+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                        val dy=500f+((y+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                        val old=a.getPixel(x,y);val now=b.getPixel(x,y)
                        if(dy>=392f) {
                            lowerChecked++
                            if(old!=now) {
                                // Clipping can retessellate a few raw oval-seam AA pixels just
                                // below390. They are permitted only when invisible behind the
                                // exact unchanged, fully opaque overlay in the SAME coordinates.
                                assertEquals("Hidden seam overlay must remain exact",oldOverlay.getPixel(x,y),newOverlay.getPixel(x,y))
                                assertEquals("Any changed lower base pixel must be fully occluded at$x,$y/$side",255,oldOverlay.getPixel(x,y) ushr 24)
                            }
                            if(dx in 365f..655f && dy in 595f..675f && old!=0xff080b0e.toInt())lowerEngraving++
                        }
                        // This free background opening contains the reported horizontal arcs,
                        // not the orange12, wheels, rear upright, or any front-layer object.
                        if(dx in 501f..540f && dy in 301f..365f && old!=now) {
                            assertEquals("The stray line must reveal bare dial, not be recoloured",0xff080b0e.toInt(),now)
                            fun brightness(color:Int)=((color ushr 16) and 255)+((color ushr 8) and 255)+(color and 255)
                            assertTrue("The original sample must be brighter engraving",brightness(old)>brightness(now))
                            cleared++
                        }
                    }
                    assertTrue("Actual upper-cap strokes must be removed, not a vacuous diff",cleared>=100)
                    assertTrue("Lower preservation must cover most of the base",lowerChecked>side*side/2)
                    assertTrue("Actual lower engraved pixels remain present",lowerEngraving>side/2)
                } finally {a.recycle();b.recycle();oldOverlay.recycle();newOverlay.recycle()}
            }
        } finally {previous.release();current.release()}
    }

    private fun drawLayer(art:Any,name:String,side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {
        val c=Canvas(it);c.scale(side/1000f,side/1000f)
        if(name=="upper")c.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        art.javaClass.getDeclaredMethod(name,Canvas::class.java).apply {isAccessible=true}.invoke(art,c)
    }
}
