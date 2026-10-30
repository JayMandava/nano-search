package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonSideSeatScaleReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private val current=CarbonDialArt()
    private val previous=BaselineSidePolishCarbonDialArt()
    @After fun release(){current.release();previous.release()}
    private fun render(side:Int,old:Boolean=false,band:BatteryBand?=BatteryBand.MEDIUM,lume:Float=0f):Bitmap {
        val b=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);Canvas(b).drawColor(0xff161b20.toInt())
        val r=RectF(0f,0f,side.toFloat(),side.toFloat())
        if(old){previous.draw(Canvas(b),r,epoch,motion=false,batteryBand=band,lumeAlpha=lume)}
        else {current.draw(Canvas(b),r,epoch,motion=false,batteryBand=band,lumeAlpha=lume)}
        return b
    }
    @Test fun frontCounterGeometryAndAcceptedSixPlaneStayUnchanged() {
        val before=File("src/test/java/ai/nanosearch/launcher/clocks/BaselineSidePolishCarbonDialArt.kt").readText().replace("BaselineSidePolishCarbonDialGeometry","CarbonDialGeometry")
        val after=File("src/main/java/ai/nanosearch/launcher/clocks/CarbonDialArt.kt").readText()
        fun part(s:String,a:String,b:String)=s.substringAfter(a).substringBefore(b)
        assertEquals(part(before,"private fun subDial(","private fun date("),part(after,"private fun subDial(","private fun date("))
        assertEquals(part(before,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"),part(after,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"))
    }
    @Test fun allGlyphCornersAreInsideClearChannelWithOriginalValues() {
        assertEquals(listOf("65","70","80","90","105","120","180","240","360","720"),CarbonScaleLabels.labels.map {it.value})
        for(label in CarbonScaleLabels.labels) {
            val outside=Path(label.box);assertTrue(outside.op(CarbonScaleLabels.safeChannel(),Path.Op.DIFFERENCE));assertTrue("Whole rotated glyph bounds ${label.value}",outside.isEmpty)
            assertTrue(label.size>=11f)
        }
    }
    @Test fun acceptedSixFacesBatteryHardwareAndEverythingOutsideJustifiedRegionsStayExact() {
        for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
            val a=render(side,true,band,lume);val b=render(side,false,band,lume)
            try {
                var changes=0
                val channel=CarbonPusherGeometry.innerDialPath().apply {transform(Matrix().apply {setScale(1.20f,1.18f,500f,500f)})}
                val deep=CarbonPusherGeometry.innerDialPath().apply {transform(Matrix().apply {setScale(1.015f,1.015f,500f,500f)})}
                val region=Region().apply {setPath(channel,Region(0,0,1000,1000))};val innerRegion=Region().apply {setPath(deep,Region(0,0,1000,1000))}
                for(y in 0 until side)for(x in 0 until side) {
                    val dx=500f+((x+.5f)*1000/side-500f)/1.05f;val dy=500f+((y+.5f)*1000/side-500f)/1.05f
                    val sideJoin=(dx in 210f..310f || dx in 690f..790f)&&dy in 365f..585f
                    // The complete outer label channel, including removed old ink and AA.
                    val scaleInk=region.contains(dx.toInt(),dy.toInt()) && !innerRegion.contains(dx.toInt(),dy.toInt())
                    val face=listOf(350f to 465f,650f to 465f).any {(cx,cy)->(dx-cx)*(dx-cx)+(dy-cy)*(dy-cy)<91f*91f}
                    val six=dx in 390f..610f && dy in 580f..787.5f
                    if((!sideJoin && !scaleInk)||face||six)assertEquals("Protected $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))
                    if(a.getPixel(x,y)!=b.getPixel(x,y))changes++
                }
                assertTrue(changes>30)
            }finally{a.recycle();b.recycle()}
        }
    }
    @Test fun longContactSilverDisappearsBehindOpaqueDarkBezelAtBothSides() {
        for(side in listOf(360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
            val b=render(side,false,band,lume);val a=render(side,true,band,lume)
            try {
                var replacedSilver=0
                for(y in listOf(420f,435f,450f,465f,480f,490f,510f)) {
                    val inner=350f-kotlin.math.sqrt(103f*103f-(y-465f)*(y-465f))
                    for(x in listOf(inner-.75f,1000f-inner+.75f)) {
                        val px=((500f+(x-500f)*1.05f)*side/1000).toInt();val py=((500f+(y-500f)*1.05f)*side/1000).toInt()
                        val color=b.getPixel(px,py)
                        if(Color.red(a.getPixel(px,py))>100)replacedSilver++
                        assertTrue("No exposed silver at $x,$y/$side/$band/$lume: $color",Color.red(color)<100)
                    }
                }
                assertTrue("Regression must reject old exposed collar",replacedSilver>=4)
            }finally{a.recycle();b.recycle()}
        }
    }
    @Test fun exportNativeReview() {
        val output=System.getenv("CARBON_SIDE_SCALE_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty());val dir=File(output);check(dir.mkdirs())
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        for(side in listOf(240,360,720,1000))for(old in listOf(true,false)) {
            val b=render(side,old);try{write("${if(old)"before" else "after"}-$side.png",b)}finally{b.recycle()}
        }
    }
}
