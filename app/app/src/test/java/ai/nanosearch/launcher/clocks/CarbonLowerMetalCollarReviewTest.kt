package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Historical proof of the rejected over-bezel attempt at b4a7b613.
// Current foreground occlusion is guarded by CarbonBezelOcclusionReviewTest.
class CarbonLowerMetalCollarReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun point(x:Int,y:Int,side:Int)=Pair(500f+((x+.5f)*1000/side-500f)/1.05f,500f+((y+.5f)*1000/side-500f)/1.05f)
    private fun raster(path:Path,side:Int):Region {
        val p=Path(path).apply {transform(Matrix().apply {setScale(1.05f,1.05f,500f,500f)});transform(Matrix().apply {setScale(side/1000f,side/1000f)})}
        return Region().apply {setPath(p,Region(0,0,side,side))}
    }
    @Test fun onlyLowerCollarSectorChangesAtAllSizesBatteryBandsAndLume() {
        val old=BaselineBeforeMetalCollarCarbonDialArt();val current=BaselineBeforeBezelOcclusionCarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        val (dx,dy)=point(x,y,side)
                        val lower=dx in 412f..588f && dy in 746f..793f
                        if(!lower) {protected++;assertEquals("Unrelated pixel $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))}
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.99f);assertTrue(changed>side/20)
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
    @Test fun lowerContourHasNoCutEndsAndKeepsAnOpenCentreGap() {
        val frame=BaselineOverBezelLowerMetalCollarGeometry.frame()
        val seatBounds=RectF().also {BaselineOverBezelLowerMetalCollarGeometry.replacement().computeBounds(it,true)}
        assertTrue("Metal plus its shadow must remain inside the blue track's 785.5 outer edge",seatBounds.bottom+2.5f<785.5f)
        val native=Region().apply {setPath(frame,Region(0,0,1000,1000))}
        for(x in 438..562)assertTrue("Missing lower metal at design column $x",(750..781).any {y->native.contains(x,y)})
        fun bottom(x:Int)=(750..785).last {y->native.contains(x,y)}
        assertTrue("Lower metal must have curved rise, not a flat foot",bottom(500)>=bottom(460)+3)
        assertTrue(kotlin.math.abs(bottom(460)-bottom(540))<=1)
        for((x,y) in listOf(448 to 770,500 to 779,552 to 770))assertTrue("Former cut end or bottom seat is missing",native.contains(x,y))
        for(side in listOf(240,360,720,1000)) {
            val actual=raster(BaselineOverBezelLowerMetalCollarGeometry.composedFrame(),side)
            val gap=raster(Path().apply {addRect(484f,563f,516f,595f,Path.Direction.CW)},side)
            assertTrue("Centre must remain disconnected from six",Region(actual).apply {op(gap,Region.Op.INTERSECT)}.isEmpty)
            val lowerClear=raster(Path().apply {addCircle(500f,693f,82f,Path.Direction.CW)},side)
            assertTrue("Counter interior must remain clear",Region(actual).apply {op(lowerClear,Region.Op.INTERSECT)}.isEmpty)
            val pixels=BooleanArray(side*side) {actual.contains(it%side,it/side)}
            val queue=IntArray(pixels.size);var pieces=0
            for(start in pixels.indices)if(pixels[start]) {
                pieces++;var head=0;var tail=0;queue[tail++]=start;pixels[start]=false
                while(head<tail) {
                    val i=queue[head++];val x=i%side;val y=i/side
                    for(n in intArrayOf(if(x>0)i-1 else -1,if(x+1<side)i+1 else -1,if(y>0)i-side else -1,if(y+1<side)i+side else -1)) {
                        if(n>=0 && pixels[n]) {pixels[n]=false;queue[tail++]=n}
                    }
                }
            }
            assertEquals("Main bridge and complete independent lower collar; no fragments",2,pieces)
        }
    }
    @Test fun restoredMaterialIsMetalAndOriginalFrontSilverRimsStayExact() {
        val old=BaselineBeforeMetalCollarCarbonDialArt();val current=BaselineBeforeBezelOcclusionCarbonDialArt()
        val a=bitmap(1000);val b=bitmap(1000)
        try {
            old.draw(Canvas(a),bounds(1000),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
            current.draw(Canvas(b),bounds(1000),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
            for((cx,cy,rad) in listOf(Triple(350f,465f,96f),Triple(650f,465f,96f),Triple(500f,693f,83f)))for(angle in 0 until 360 step 5) {
                val t=Math.toRadians(angle.toDouble());val x=kotlin.math.round(500+(cx+rad*kotlin.math.cos(t)-500)*1.05).toInt();val y=kotlin.math.round(500+(cy+rad*kotlin.math.sin(t)-500)*1.05).toInt()
                assertEquals("Original silver circle changed at $cx,$cy/$angle",a.getPixel(x,y),b.getPixel(x,y))
            }
            for((dx,dy) in listOf(448f to 770f,500f to 779.5f,552f to 770f)) {
                val x=kotlin.math.round(500+(dx-500)*1.05).toInt();val y=kotlin.math.round(500+(dy-500)*1.05).toInt();val color=b.getPixel(x,y)
                val channels=listOf(Color.red(color),Color.green(color),Color.blue(color))
                assertTrue("Restored seat must be neutral metal rather than blue track or empty black: $dx,$dy/$channels",channels.min()>75 && channels.max()-channels.min()<45)
            }
        }finally{a.recycle();b.recycle();old.release();current.release()}
    }
    @Test fun exportNativeMetalCollarComparison() {
        val output=System.getenv("CARBON_METAL_COLLAR_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs());val old=BaselineBeforeMetalCollarCarbonDialArt();val current=BaselineBeforeBezelOcclusionCarbonDialArt()
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        fun compare(a:Bitmap,b:Bitmap,title:String)=Bitmap.createBitmap(a.width+b.width+30,maxOf(a.height,b.height)+80,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);c.drawColor(0xff161b20.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
            c.drawText("BEFORE · 1cbbcf7",10f,28f,p);c.drawText(title,a.width+20f,28f,p)
            c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null)
        }
        var full:Bitmap?=null;var detail:Bitmap?=null
        try {
            for(side in listOf(240,360,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    write("before-$side.png",a);write("after-$side.png",b)
                    if(side==720)full=compare(a,b,"CONTINUOUS LOWER METAL")
                    if(side==1000) {
                        fun crop(source:Bitmap)=Bitmap.createBitmap(720,750,Bitmap.Config.ARGB_8888).also {Canvas(it).drawBitmap(source,Rect(380,575,620,825),Rect(0,0,720,750),Paint().apply {isFilterBitmap=false})}
                        val da=crop(a);val db=crop(b)
                        try{detail=compare(da,db,"LOWER COLLAR · 3× NATIVE PIXELS")}finally{da.recycle();db.recycle()}
                    }
                }finally{a.recycle();b.recycle()}
            }
            val f=requireNotNull(full);val d=requireNotNull(detail)
            val sheet=Bitmap.createBitmap(maxOf(f.width,d.width),f.height+d.height+20,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(sheet);c.drawColor(0xff161b20.toInt());c.drawBitmap(f,0f,0f,null);c.drawBitmap(d,0f,f.height.toFloat(),null)
                write("Nano-continuous-lower-metal-collar-review.png",sheet)
            }finally{sheet.recycle()}
        }finally{full?.recycle();detail?.recycle();old.release();current.release()}
    }
}
