package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.io.File
import java.time.Instant
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Candidate from coordinator-measured reference, not an original-image inspection on this executor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceReviewCandidateTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun batteryArea(x:Float,y:Float)=x in 615f..725f && y in 625f..752f
    private fun point(x:Int,y:Int,side:Int)=Pair(500f+((x+.5f)*1000f/side-500f)/1.05f,500f+((y+.5f)*1000f/side-500f)/1.05f)

    @Test fun allApprovedInnerFacePixelsStayExactOutsideBatteryForEveryBandAndSize() {
        // Historical case/battery acceptance proof. The subsequent six-o'clock
        // tangent change is bounded against this snapshot by its own pixel test.
        val old=Baseline084CarbonDialArt();val current=BaselineBeforeSixSeatCarbonDialArt()
        val protectedPath=Path().apply {
            // Fixed approved dial envelope, never inferred from revised case or pixels.
            moveTo(327f,240f);quadTo(500f,220f,673f,240f)
            cubicTo(724f,290f,766f,405f,760f,500f)
            cubicTo(766f,600f,724f,720f,673f,770f)
            quadTo(500f,790f,327f,770f)
            cubicTo(276f,720f,234f,600f,240f,500f)
            cubicTo(234f,405f,276f,290f,327f,240f);close()
            transform(Matrix().apply {setScale(1.10f,1.07f,500f,500f)})
        }
        val face=Region().apply {setPath(protectedPath,Region(0,0,1000,1000))}
        try {
            for(side in listOf(240,360,720,1000)) for(band in listOf(null,*BatteryBand.entries.toTypedArray())) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band?.let {Baseline084BatteryBand.valueOf(it.name)})
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band)
                    var checked=0;var changed=0
                    for(y in 0 until side) for(x in 0 until side) {
                        val (dx,dy)=point(x,y,side)
                        if(face.contains(dx.toInt(),dy.toInt()) && !batteryArea(dx,dy)) {
                            checked++;assertEquals("Approved face changed $x,$y/$side/$band",a.getPixel(x,y),b.getPixel(x,y))
                        }
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue("Substantial exact inner-face coverage: $checked",checked>side*side/4);assertTrue(changed>side)
                } finally {a.recycle();b.recycle()}
            }
        } finally {old.release();current.release()}
    }

    @Test fun pointerBandsAreRadialAndConfinedWhileUnknownLeavesScale() {
        val art=CarbonDialArt();val images=mutableListOf<Bitmap>()
        try {
            for(band in listOf(null,*BatteryBand.entries.toTypedArray())) {
                val b=bitmap(720);images+=b;art.draw(Canvas(b),bounds(720),epoch,motion=false,batteryBand=band)
            }
            for(index in 1..3) {
                assertFalse(images[0].sameAs(images[index]))
                for(y in 0 until 720) for(x in 0 until 720) {
                    val (dx,dy)=point(x,y,720)
                    if(!batteryArea(dx,dy))assertEquals(images[0].getPixel(x,y),images[index].getPixel(x,y))
                }
            }
            for(band in BatteryBand.entries) {
                val (x,y)=CarbonBatteryGeometry.point(band.angle,CarbonBatteryGeometry.SCALE_RADIUS)
                assertTrue("Every sector lies left of the pivot",x<CarbonBatteryGeometry.PIVOT_X)
                if(band!=BatteryBand.LOW)assertTrue("Medium/high sectors lie above pivot",y<CarbonBatteryGeometry.PIVOT_Y)
            }
        } finally {images.forEach(Bitmap::recycle);art.release()}
    }

    @Test fun boundaryAndUnknownReadingsDriveActualCurrentRenderer() {
        val renderer=CarbonClockFaceRenderer();val images=mutableListOf<Bitmap>()
        try {
            for(level in listOf<Int?>(null,0,20,21,70,71,100,-1,101)) {
                renderer.onBatteryChanged(level)
                val b=bitmap(360);images+=b;renderer.draw(Canvas(b),bounds(360),epoch,false)
            }
            assertTrue(images[1].sameAs(images[2]));assertTrue(images[3].sameAs(images[4]));assertTrue(images[5].sameAs(images[6]))
            assertTrue(images[0].sameAs(images[7]));assertTrue(images[0].sameAs(images[8]))
            for(index in listOf(1,3,5))assertFalse(images[0].sameAs(images[index]))
        } finally {images.forEach(Bitmap::recycle);renderer.release()}
    }

    @Test fun frozenPreviousCandidateAndApprovedBridgeRetainExactSourceHashes() {
        val fixture=File("src/test/java/ai/nanosearch/launcher/clocks/Baseline084CarbonDialArt.kt").readText()
            .replace("Baseline084CarbonDialArt","CarbonDialArt").replace("Baseline084CarbonDialGeometry","CarbonDialGeometry").replace("Baseline084BatteryBand","BatteryBand")
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        assertEquals("0c6c45e3eebb14916cdaf0aa1a1bf85a3d94722aff34a65948bae12c0fe22556",sha(fixture.toByteArray(Charsets.UTF_8)))
        assertEquals("0286c6b3ea818f6b0b41eb3aab7c4d01415c5ee17dcf372973660f33b64411a0",sha(File("src/test/resources/clock_history/CarbonBridgeArt-1e3bfdf.kt.txt").readBytes()))
        // Historical source proof: new fourth-pusher hit geometry is checked by FourthPusherReviewTest.
        assertEquals("1079d8adcd097aef726dc6cedc2c06134f2af92a76070ccccd29027b041a6f44",sha(File("src/test/resources/clock_history/CarbonPusherGeometry-5449ca3.kt.txt").readBytes()))
    }

    @Test fun caseFastenersHaveMaterialAndExistingPusherTargetsRemainCoherent() {
        val art=CarbonDialArt()
        val outline=art.javaClass.getDeclaredMethod("tonneau",java.lang.Float.TYPE).apply {isAccessible=true}.invoke(art,0f) as Path
        val material=Region().apply {setPath(outline,Region(0,0,1000,1000))}
        try {
            for((x,y) in listOf(245f to 92f,388f to 75f,612f to 75f,755f to 92f,860f to 285f,884f to 500f,860f to 715f,755f to 908f,612f to 925f,388f to 925f,245f to 908f,140f to 715f)) {
                val circle=Region().apply {setPath(Path().apply {addCircle(x,y,20f,Path.Direction.CW)},Region(0,0,1000,1000))}
                assertTrue("Existing case screw seat outside new outline at $x,$y",Region(circle).apply {op(material,Region.Op.DIFFERENCE)}.isEmpty)
            }
            for(side in listOf(240,360,720,1000)) {
                val layout=CarbonPusherGeometry.layout(side.toFloat(),side.toFloat(),1f)
                for((action,xy) in listOf(CarbonPusherAction.START_PAUSE to (905f to 339f),CarbonPusherAction.RESET to (905f to 668f),CarbonPusherAction.SPLIT_RELEASE to (85f to 691.5f),CarbonPusherAction.LUME to (950f to 500f))) {
                    assertEquals(action,layout.hitTest(xy.first*side/1000f,xy.second*side/1000f))
                    val target=layout.boundsFor(action);assertTrue(target.width()>=47.99f)
                    assertTrue(target.height()>= (if(action==CarbonPusherAction.LUME) minOf(48f,side*.16f) else 48f)-.01f)
                }
            }
        } finally {art.release()}
    }

    @Test fun exportNativeFullFaceAndUnretouchedDetailsForCoordinatorReview() {
        val output=System.getenv("CARBON_REFERENCE_CANDIDATE_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs())
        val old=Baseline084CarbonDialArt();val current=CarbonDialArt()
        fun write(name:String,b:Bitmap) {File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
        fun compare(name:String,a:Bitmap,b:Bitmap) {
            val sheet=Bitmap.createBitmap(a.width*2+30,a.height+90,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(sheet);c.drawColor(0xff161b20.toInt())
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
                c.drawText("BEFORE · 11166bf",10f,28f,p);c.drawText("CANDIDATE · "+listOf("UNKNOWN","MEDIUM","HIGH","LOW").firstOrNull {name.contains(it)}.orEmpty(),a.width+20f,28f,p)
                c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null);write(name,sheet)
            } finally {sheet.recycle()}
        }
        fun detail(source:Bitmap,rect:Rect,scale:Int):Bitmap {
            val out=Bitmap.createBitmap(rect.width()*scale,rect.height()*scale,Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(source,rect,Rect(0,0,out.width,out.height),Paint().apply {isFilterBitmap=false})
            return out
        }
        try {
            for(side in listOf(240,360,720)) for(band in listOf(null,*BatteryBand.entries.toTypedArray())) {
                val a=bitmap(side);val b=bitmap(side)
                val state=band?.name ?: "UNKNOWN"
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band?.let {Baseline084BatteryBand.valueOf(it.name)})
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band)
                    write("before-$state-$side.png",a);write("after-$state-$side.png",b)
                    if(side==720) {
                        if(band==BatteryBand.MEDIUM) compare("comparison-full-720.png",a,b)
                        for((label,rect) in listOf("battery" to Rect(432,438,546,566),"pushers" to Rect(590,170,710,555))) {
                            val da=detail(a,rect,3);val db=detail(b,rect,3)
                            try {compare("comparison-$label-$state-3x.png",da,db)} finally {da.recycle();db.recycle()}
                        }
                    }
                } finally {a.recycle();b.recycle()}
            }
            val full=BitmapFactory.decodeFile(File(dir,"comparison-full-720.png").path)
            val details=listOf("UNKNOWN","LOW","MEDIUM","HIGH").map {BitmapFactory.decodeFile(File(dir,"comparison-battery-$it-3x.png").path)}
            val pushers=BitmapFactory.decodeFile(File(dir,"comparison-pushers-MEDIUM-3x.png").path)
            val overview=Bitmap.createBitmap(full.width,full.height+details.sumOf {it.height}+24,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(overview);c.drawColor(0xff161b20.toInt());c.drawBitmap(full,0f,0f,null)
                var y=full.height.toFloat()
                for(detail in details) {c.drawBitmap(detail,0f,y,null);y+=detail.height}
                c.drawBitmap(pushers,details.first().width+8f,full.height.toFloat(),null)
                write("Nano-battery-case-review-candidate.png",overview)
            } finally {full.recycle();details.forEach(Bitmap::recycle);pushers.recycle();overview.recycle()}
            File(dir,"reference-analysis.txt").writeText("Review-only working tree. Original reference inspected by coordinating executor, who supplied measured geometry. This executor did not inspect original reference pixels. Native Android Canvas via Robolectric API35/native Skia; not device screenshots. Detail crops enlarged 3x nearest-neighbour without retouching. No commit, signing, push, dispatch or release preparation at this review stage.")
        } finally {old.release();current.release()}
    }
}
