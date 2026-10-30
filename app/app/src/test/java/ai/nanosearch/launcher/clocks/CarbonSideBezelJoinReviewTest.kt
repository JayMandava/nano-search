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
class CarbonSideBezelJoinReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun point(x:Int,y:Int,side:Int)=Pair(500f+((x+.5f)*1000/side-500f)/1.05f,500f+((y+.5f)*1000/side-500f)/1.05f)
    @Test fun everyUnrelatedPixelIncludingAcceptedSixAndAllCounterFacesStaysExact() {
        val old=BaselineAcceptedSixCarbonDialArt();val current=CarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        val (dx,dy)=point(x,y,side)
                        val join=(dx in 222f..282f || dx in 718f..778f) && dy in 370f..580f
                        val face=listOf(350f to 465f,650f to 465f).any {(cx,cy)->(dx-cx)*(dx-cx)+(dy-cy)*(dy-cy)<91f*91f}
                        if(!join || face) {protected++;assertEquals("Unrelated/accepted six/counter face pixel $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))}
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.97f);assertTrue("Side polish must affect actual pixels",changed>3)
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
    @Test fun existingAppliedSideMarkersSitOverTheClearanceRatherThanAbutItsBlackEdge() {
        val old=BaselineAcceptedSixCarbonDialArt();val current=CarbonDialArt();val a=bitmap(1000);val b=bitmap(1000)
        try {
            old.draw(Canvas(a),bounds(1000),epoch,motion=false)
            current.draw(Canvas(b),bounds(1000),epoch,motion=false)
            for((x,y) in listOf(246 to 500,754 to 500)) {
                val before=a.getPixel(x,y);val after=b.getPixel(x,y)
                assertTrue("Existing marker must cover the old dark join at $x,$y",Color.red(after)>210 && Color.green(after)>210 && Color.blue(after)>210)
                assertTrue("Marker contact must actually improve",Color.red(after)>Color.red(before)+15)
            }
        }finally{old.release();current.release();a.recycle();b.recycle()}
    }
    @Test fun ringGeometryTrackAndAcceptedSixPlaneSourceAreUnchanged() {
        val before=File("src/test/java/ai/nanosearch/launcher/clocks/BaselineAcceptedSixCarbonDialArt.kt").readText().replace("BaselineAcceptedSixCarbonDialGeometry","CarbonDialGeometry")
        val current=File("src/main/java/ai/nanosearch/launcher/clocks/CarbonDialArt.kt").readText()
        fun section(s:String,a:String,b:String)=s.substringAfter(a).substringBefore(b)
        assertEquals(section(before,"private fun track(c: Canvas)","private fun date(c:Canvas"),section(current,"private fun track(c: Canvas)","private fun date(c:Canvas"))
        assertEquals(section(before,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"),section(current,"val foreground=Bitmap.createBitmap","}finally{foreground.recycle()}"))
    }
    @Test fun exportNativeFullFaceSideJoinsAndUnchangedSixControl() {
        val output=System.getenv("CARBON_SIDE_JOIN_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs());val old=BaselineAcceptedSixCarbonDialArt();val current=CarbonDialArt();val panels=mutableListOf<Bitmap>()
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        fun pair(a:Bitmap,b:Bitmap,title:String)=Bitmap.createBitmap(a.width+b.width+30,a.height+80,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);c.drawColor(0xff161b20.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
            c.drawText("BEFORE · 2705e27",10f,28f,p);c.drawText(title,a.width+20f,28f,p);c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null)
        }
        try {
            for(side in listOf(240,360,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    write("before-$side.png",a);write("after-$side.png",b)
                    if(side==720)panels+=pair(a,b,"SIDE JOINS · FOREGROUND")
                    if(side==1000)for((label,r,scale) in listOf(Triple("9 JOIN · 3×",Rect(210,380,325,565),3),Triple("3 JOIN · 3×",Rect(675,380,790,565),3),Triple("SIX · EXACTLY UNCHANGED",Rect(380,575,620,825),2))) {
                        fun crop(src:Bitmap)=Bitmap.createBitmap(r.width()*scale,r.height()*scale,Bitmap.Config.ARGB_8888).also {Canvas(it).drawBitmap(src,r,Rect(0,0,it.width,it.height),Paint().apply {isFilterBitmap=false})}
                        val da=crop(a);val db=crop(b)
                        try{if(label.startsWith("SIX"))assertTrue(da.sameAs(db));panels+=pair(da,db,label)}finally{da.recycle();db.recycle()}
                    }
                }finally{a.recycle();b.recycle()}
            }
            val height=panels[0].height+maxOf(panels[1].height,panels[2].height)+panels[3].height+30
            val sheet=Bitmap.createBitmap(maxOf(panels[0].width,panels[1].width+panels[2].width+10),height,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(sheet);c.drawColor(0xff161b20.toInt());c.drawBitmap(panels[0],0f,0f,null)
                val y=panels[0].height.toFloat();c.drawBitmap(panels[1],0f,y,null);c.drawBitmap(panels[2],panels[1].width+10f,y,null)
                c.drawBitmap(panels[3],0f,y+maxOf(panels[1].height,panels[2].height),null);write("Nano-side-bezel-join-polish-review.png",sheet)
            }finally{sheet.recycle()}
        }finally{panels.forEach(Bitmap::recycle);old.release();current.release()}
    }
}
