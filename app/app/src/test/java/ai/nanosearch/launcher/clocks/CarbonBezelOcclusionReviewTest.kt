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
// Exact historical proof of the accepted six-o-clock revision at 2705e272.
// The subsequent side polish is protected by CarbonSideBezelJoinReviewTest.
class CarbonBezelOcclusionReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun point(x:Int,y:Int,side:Int)=Pair(500f+((x+.5f)*1000/side-500f)/1.05f,500f+((y+.5f)*1000/side-500f)/1.05f)
    private data class Foreground(val image:Bitmap,val opaque:BooleanArray,val cachePixels:IntArray,val cacheSide:Int)
    private fun foregroundOnly(side:Int,lume:Float):Foreground {
        val art=BaselineAcceptedSixCarbonDialArt();val cache=bitmap(side.coerceIn(256,1536));val clipMask=bitmap(cache.width);val out=bitmap(side)
        try {
            val c=Canvas(cache);c.scale(cache.width/1000f,cache.height/1000f);c.scale(1.05f,1.05f,500f,500f)
            // The foreground plane is the bezel's footprint, including only
            // the portions of existing markers actually over that plane.
            // Its opaque pixels must survive over any ring/background.
            val footprint=Path()
            Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=11f;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}.getFillPath(CarbonPusherGeometry.innerDialPath(),footprint)
            c.clipRect(460f,768f,540f,794f);c.clipPath(footprint)
            Canvas(clipMask).apply {scale(cache.width/1000f,cache.height/1000f);scale(1.05f,1.05f,500f,500f);clipPath(footprint);drawColor(Color.WHITE)}
            art.javaClass.getDeclaredMethod("track",Canvas::class.java).apply {isAccessible=true}.invoke(art,c)
            // Match the renderer's matrix and select pixels whose complete
            // bilinear footprint is opaque before scaling. A final alpha byte
            // rounded to 255 alone can include translucent edge contributions.
            Canvas(out).apply {scale(side/1000f,side/1000f)}.drawBitmap(cache,null,RectF(0f,0f,1000f,1000f),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if(lume>0f) {
                // Existing hour-marker lume is intentionally above the bezel.
                val glow=Canvas(out).apply {scale(side/1000f,side/1000f);scale(1.05f,1.05f,500f,500f)}
                val angles=CarbonTimeModel.angles(epoch,java.util.TimeZone.getDefault())
                art.javaClass.getDeclaredMethod("drawLume",Canvas::class.java,java.lang.Float.TYPE,java.lang.Float.TYPE,java.lang.Float.TYPE).apply {isAccessible=true}.invoke(art,glow,angles.hour,angles.minute,lume)
            }
            val opaque=BooleanArray(side*side) { i ->
                val xx=(i%side+.5)*cache.width/side-.5;val yy=(i/side+.5)*cache.height/side-.5
                val x=kotlin.math.floor(xx).toInt();val y=kotlin.math.floor(yy).toInt()
                x>=0 && y>=0 && x+1<cache.width && y+1<cache.height &&
                    (0..1).all {oy->(0..1).all {ox->Color.alpha(clipMask.getPixel(x+ox,y+oy))==255}}
            }
            val pixels=IntArray(cache.width*cache.height);cache.getPixels(pixels,0,cache.width,0,0,cache.width,cache.height)
            return Foreground(out,opaque,pixels,cache.width)
        }finally{cache.recycle();clipMask.recycle();art.release()}
    }
    @Test fun foregroundBezelAndExistingMarkersOccludeMetalAtAllThreeJoins() {
        val art=BaselineAcceptedSixCarbonDialArt();val sideControl=BaselineBeforeBezelOcclusionCarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000)) {
                val expectedStates=listOf(foregroundOnly(side,0f),foregroundOnly(side,1f))
                try {
                    for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                        val expected=expectedStates[if(lume==0f)0 else 1]
                        val actual=bitmap(side);val acceptedSide=bitmap(side)
                        try {
                            art.draw(Canvas(actual),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                            sideControl.draw(Canvas(acceptedSide),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                            val checks=IntArray(3)
                            for(y in 0 until side)for(x in 0 until side) {
                                val (dx,dy)=point(x,y,side)
                                val join=when {
                                    dx in 440f..560f && dy in 775f..784f -> 0
                                    dx in 236f..244f && dy in 400f..530f -> 1
                                    dx in 756f..764f && dy in 400f..530f -> 2
                                    else -> -1
                                }
                                val color=expected.image.getPixel(x,y)
                                if(join>=0 && expected.opaque[y*side+x] && (join>0 || Color.alpha(color)==255)) {
                                    checks[join]++
                                    val seen=actual.getPixel(x,y)
                                    if(join>0) {
                                        // Side joins are accepted controls and
                                        // must stay bit-exact, including AA.
                                        assertEquals("Accepted side bezel changed",acceptedSide.getPixel(x,y),seen)
                                        continue
                                    }
                                    // Isolating the clipped plane changes integer
                                    // premultiplication rounding at filtered tick
                                    // edges by 1–2 levels. A metal/black overlay is
                                    // far outside this bound; unrelated pixels
                                    // remain subject to exact comparison below.
                                    val error=maxOf(kotlin.math.abs(Color.red(color)-Color.red(seen)),kotlin.math.abs(Color.green(color)-Color.green(seen)),kotlin.math.abs(Color.blue(color)-Color.blue(seen)))
                                    if(error>2) {
                                        val overlay=art.javaClass.getDeclaredField("overlay").apply {isAccessible=true}.get(art) as Bitmap
                                        val cx=kotlin.math.floor((x+.5)*expected.cacheSide/side-.5).toInt();val cy=kotlin.math.floor((y+.5)*expected.cacheSide/side-.5).toInt()
                                        val raw=(0..1).flatMap {oy->(0..1).map {ox->Pair(expected.cachePixels[(cy+oy)*expected.cacheSide+cx+ox],overlay.getPixel(cx+ox,cy+oy))}}
                                        fail("Foreground bezel/marker covered at join $join, $x,$y/$side/$band/$lume: $color -> $seen; cache $cx,$cy expected/actual=$raw")
                                    }
                                }
                            }
                            for(n in checks)assertTrue("Occlusion check must sample real opaque bezel pixels: ${checks.toList()}",n>side/50)
                        }finally{actual.recycle();acceptedSide.recycle()}
                    }
                }finally{expectedStates.forEach {it.image.recycle()}}
            }
        }finally{art.release();sideControl.release()}
    }
    @Test fun unrelatedPixelsAndCounterFacesStayExactAcrossAllStates() {
        val old=BaselineBeforeBezelOcclusionCarbonDialArt();val art=BaselineAcceptedSixCarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    art.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        val (dx,dy)=point(x,y,side)
                        val lower=dx in 412f..588f && dy in 746f..800f
                        val counterFace=(dx-500)*(dx-500)+(dy-693)*(dy-693)<77f*77f
                        if(!lower || counterFace) {protected++;assertEquals("Unrelated or counter-face pixel changed $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))}
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.985f);assertTrue(changed>side/20)
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();art.release()}
    }
    @Test fun foregroundContractRejectsThePreviousMetalOverBezelCandidate() {
        val rejected=BaselineBeforeBezelOcclusionCarbonDialArt();val bad=bitmap(1000);val expected=foregroundOnly(1000,0f)
        try {
            rejected.draw(Canvas(bad),bounds(1000),epoch,motion=false)
            var covered=0
            for(y in 0 until 1000)for(x in 0 until 1000) {
                val (dx,dy)=point(x,y,1000)
                if(dx in 460f..540f && dy in 775f..784f && expected.opaque[y*1000+x]) {
                    val a=expected.image.getPixel(x,y);val b=bad.getPixel(x,y)
                    if(Color.alpha(a)==255 && maxOf(kotlin.math.abs(Color.red(a)-Color.red(b)),kotlin.math.abs(Color.green(a)-Color.green(b)),kotlin.math.abs(Color.blue(a)-Color.blue(b)))>8)covered++
                }
            }
            assertTrue("The owner's rejected metal-over-blue image must fail this contract: $covered",covered>20)
        }finally{bad.recycle();expected.image.recycle();rejected.release()}
    }
    @Test fun lowerRingRemainsCircularBehindCaseAndCentreBridgeStaysSeparate() {
        val ring=Region().apply {setPath(CarbonLowerMetalCollarGeometry.frame(),Region(0,0,1000,1000))}
        assertTrue("The lower circle must continue behind the occluder, not flatten at y780",ring.contains(500,786))
        assertFalse(ring.contains(500,780)) // r87 remains its original clear aperture.
        val actual=Region().apply {setPath(CarbonLowerMetalCollarGeometry.composedFrame(),Region(0,0,1000,1000))}
        // Independent Path-op and Region conic rasters may disagree at the
        // boundary by a pixel. Actual foreground-plane coverage is checked
        // exactly above; this geometric envelope excludes real protrusions.
        val envelope=CarbonPusherGeometry.innerDialPath()
        val edge=Path();Paint().apply {style=Paint.Style.STROKE;strokeWidth=2f}.getFillPath(envelope,edge)
        envelope.op(edge,Path.Op.UNION)
        val dial=Region().apply {setPath(envelope,Region(0,0,1000,1000))}
        assertTrue("Hidden ring may not exceed the case edge's AA envelope",Region(actual).apply {op(dial,Region.Op.DIFFERENCE)}.isEmpty)
        for(y in 568..590)for(x in 480..520)assertFalse("No centre-to-six bridge",actual.contains(x,y))
    }
    @Test fun exportActualLowerAndSideOcclusionComparisons() {
        val output=System.getenv("CARBON_BEZEL_OCCLUSION_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs());val old=BaselineBeforeBezelOcclusionCarbonDialArt();val art=BaselineAcceptedSixCarbonDialArt()
        val sheets=mutableListOf<Bitmap>()
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        fun pair(a:Bitmap,b:Bitmap,title:String)=bitmapPair(a,b,title)
        try {
            for(side in listOf(240,360,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    art.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    write("before-$side.png",a);write("after-$side.png",b)
                    if(side==720)sheets+=pair(a,b,"BEZEL IN FRONT")
                    if(side==1000)for((label,r) in listOf("LOWER · 3× RING BEHIND BEZEL" to Rect(380,575,620,825),"9 SIDE · 3×" to Rect(215,390,280,560),"3 SIDE · 3×" to Rect(720,390,785,560))) {
                        fun crop(src:Bitmap)=Bitmap.createBitmap(r.width()*3,r.height()*3,Bitmap.Config.ARGB_8888).also {Canvas(it).drawBitmap(src,r,Rect(0,0,it.width,it.height),Paint().apply {isFilterBitmap=false})}
                        val da=crop(a);val db=crop(b)
                        try{sheets+=pair(da,db,label)}finally{da.recycle();db.recycle()}
                    }
                }finally{a.recycle();b.recycle()}
            }
            val height=sheets[0].height+sheets[1].height+maxOf(sheets[2].height,sheets[3].height)+30
            val sheet=Bitmap.createBitmap(maxOf(sheets[0].width,sheets[1].width,sheets[2].width+sheets[3].width+10),height,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(sheet);c.drawColor(0xff161b20.toInt());c.drawBitmap(sheets[0],0f,0f,null);c.drawBitmap(sheets[1],0f,sheets[0].height.toFloat(),null)
                val y=(sheets[0].height+sheets[1].height).toFloat();c.drawBitmap(sheets[2],0f,y,null);c.drawBitmap(sheets[3],sheets[2].width+10f,y,null)
                write("Nano-ring-behind-bezel-review.png",sheet)
            }finally{sheet.recycle()}
        }finally{sheets.forEach(Bitmap::recycle);old.release();art.release()}
    }
    private fun bitmapPair(a:Bitmap,b:Bitmap,title:String)=Bitmap.createBitmap(a.width+b.width+30,maxOf(a.height,b.height)+80,Bitmap.Config.ARGB_8888).also {
        val c=Canvas(it);c.drawColor(0xff161b20.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
        c.drawText("BEFORE · b4a7b61",10f,28f,p);c.drawText(title,a.width+20f,28f,p);c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null)
    }
}
