package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonSideCounterOverlapReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private val current=CarbonDialArt();private val before=BaselineSeatedScaleCarbonDialArt()
    @After fun release(){current.release();before.release()}
    private fun render(side:Int,old:Boolean=false,band:BatteryBand?=BatteryBand.MEDIUM,lume:Float=0f,hands:Boolean=true):Bitmap {
        val b=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(0xff161b20.toInt())
        val bounds=RectF(0f,0f,side.toFloat(),side.toFloat())
        if(old)before.draw(c,bounds,epoch,motion=false,hands=hands,batteryBand=band,lumeAlpha=lume)
        else current.draw(c,bounds,epoch,motion=false,hands=hands,batteryBand=band,lumeAlpha=lume)
        return b
    }
    @Test fun changesAreConfinedToTheMovedRegistersAndTheirShortSupportJoins() {
        for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
            val a=render(side,true,band,lume);val b=render(side,false,band,lume)
            try {
                var changes=0;var protected=0
                for(y in 0 until side)for(x in 0 until side) {
                    val dx=500f+((x+.5f)*1000/side-500f)/1.05f;val dy=500f+((y+.5f)*1000/side-500f)/1.05f
                    val register=listOf(340f,350f,650f,660f).any {cx->(dx-cx)*(dx-cx)+(dy-465f)*(dy-465f)<125f*125f}
                    val upper=(dx in 355f..450f||dx in 550f..645f)&&dy in 320f..412f
                    val lower=(dx in 275f..380f||dx in 620f..725f)&&dy in 540f..623f
                    val oldMask=(dx in 215f..305f||dx in 695f..785f)&&dy in 365f..585f
                    val six=dx in 390f..610f && dy in 580f..787.5f
                    if((!register && !upper && !lower && !oldMask)||six) {
                        protected++;assertEquals("Protected $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))
                    }
                    if(a.getPixel(x,y)!=b.getPixel(x,y))changes++
                }
                assertTrue(protected>side*side*.72f);assertTrue(changes>side)
            }finally{a.recycle();b.recycle()}
        }
    }
    @Test fun existingBluePlaneOccludesTheActuallyOverlappingFrontSilverAtMultipleScales() {
        val upperMethod=CarbonDialArt::class.java.getDeclaredMethod("upper",Canvas::class.java).apply {isAccessible=true}
        val trackMethod=CarbonDialArt::class.java.getDeclaredMethod("track",Canvas::class.java).apply {isAccessible=true}
        for(side in listOf(240,360,720,1000)) {
            val cache=side.coerceAtLeast(256)
            val rawCache=Bitmap.createBitmap(cache,cache,Bitmap.Config.ARGB_8888)
            val planeCache=Bitmap.createBitmap(cache,cache,Bitmap.Config.ARGB_8888)
            fun canvas(b:Bitmap)=Canvas(b).apply {scale(cache/1000f,cache/1000f);scale(1.05f,1.05f,500f,500f)}
            upperMethod.invoke(current,canvas(rawCache));trackMethod.invoke(current,canvas(planeCache))
            fun resize(b:Bitmap)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {Canvas(it).drawBitmap(b,null,Rect(0,0,side,side),Paint(Paint.FILTER_BITMAP_FLAG))}
            val raw=resize(rawCache);val plane=resize(planeCache);rawCache.recycle();planeCache.recycle()
            val composed=render(side,hands=false)
            try {
                var checked=0
                for(y in 0 until side)for(x in 0 until side) {
                    val dx=500f+((x+.5f)*1000/side-500f)/1.05f;val dy=500f+((y+.5f)*1000/side-500f)/1.05f
                    val expectedPixel=plane.getPixel(x,y)
                    // Compare solid material cores, not independently filtered
                    // mixed-coverage tick edges at fractional cache coordinates.
                    val solid=expectedPixel==Color.rgb(53,185,219)||expectedPixel==Color.rgb(230,234,232)
                    if((dx in 238f..243f || dx in 757f..762f) && dy in 415f..510f && solid) {
                        val expected=plane.getPixel(x,y);val actual=composed.getPixel(x,y)
                        val error=listOf(Color.red(expected)-Color.red(actual),Color.green(expected)-Color.green(actual),Color.blue(expected)-Color.blue(actual)).maxOf {kotlin.math.abs(it)}
                        assertTrue("Existing opaque foreground $x,$y/$side error=$error",error<=3);checked++
                    }
                }
                assertTrue("Opaque bezel core samples/$side: $checked",checked>=(side/100).coerceAtLeast(2));if(side==1000) {
                    var genuine=0
                    for(yy in 450..480)for(xx in listOf(243.5f,756.5f)) {
                        val px=(500f+(xx-500f)*1.05f).toInt();val py=(500f+(yy-500f)*1.05f).toInt()
                        val p=plane.getPixel(px,py);val r=raw.getPixel(px,py);val c=composed.getPixel(px,py)
                        if(Color.blue(p)-Color.red(p)>80 && Color.red(r)>120 && Color.blue(c)-Color.red(c)>80)genuine++
                    }
                    assertTrue("Native front silver must genuinely cross behind original cyan, not a dark patch: $genuine",genuine>10)
                }
            }finally{raw.recycle();plane.recycle();composed.recycle()}
        }
    }
    @Test fun circlesTranslateAsAUnitWithoutChangingRadiiContentsOrTheAcceptedSixPlane() {
        assertEquals(340f,CarbonDialGeometry.LEFT_COUNTER_X,0f);assertEquals(660f,CarbonDialGeometry.RIGHT_COUNTER_X,0f)
        val liveBridge=CarbonDialArt::class.java.getDeclaredField("bridgeArt").apply {isAccessible=true}.get(current)
        val liveOutset=CarbonBridgeArt::class.java.getDeclaredField("sideOutset").apply {isAccessible=true}.getFloat(liveBridge)
        assertEquals("The live renderer must use the approved bridge placement",10f,liveOutset,0f)
        val old=File("src/test/java/ai/nanosearch/launcher/clocks/BaselineSeatedScaleCarbonDialArt.kt").readText().replace("BaselineSeatedScaleCarbonDialGeometry","CarbonDialGeometry")
        val new=File("src/main/java/ai/nanosearch/launcher/clocks/CarbonDialArt.kt").readText()
        fun part(s:String,a:String,b:String)=s.substringAfter(a).substringBefore(b)
        assertEquals(part(old,"private fun subDial(","private fun date("),part(new,"private fun subDial(","private fun date("))
        assertEquals(part(old,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"),part(new,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"))
        assertFalse(new.contains("CarbonSideBezelSeat"));assertFalse(new.contains("fill(f,sideBezelForegroundMask()"))
        val frame=CarbonBridgeGeometry.frame(10f)
        val material=Region().apply {setPath(frame,Region(0,0,1000,1000))}
        for(cx in listOf(340f,660f)) {
            val cavity=Region().apply {setPath(Path().apply {addCircle(cx,465f,102f,Path.Direction.CW)},Region(0,0,1000,1000))}
            assertTrue("Moving the aperture with its collar prevents support intrusion",Region(cavity).apply {op(material,Region.Op.INTERSECT)}.isEmpty)
        }
    }
    @Test fun exportNativeReviewAndStateControls() {
        val output=System.getenv("CARBON_SIDE_OVERLAP_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty());val dir=File(output);check(dir.mkdirs())
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        for(side in listOf(240,360,720,1000)) {
            for(old in listOf(true,false)) {val b=render(side,old);try{write("${if(old)"before" else "after"}-$side.png",b)}finally{b.recycle()}}
            for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                val b=render(side,band=band,lume=lume);try{write("state-${band?.name?:"UNKNOWN"}-${lume.toInt()}-$side.png",b)}finally{b.recycle()}
            }
        }
    }
}
