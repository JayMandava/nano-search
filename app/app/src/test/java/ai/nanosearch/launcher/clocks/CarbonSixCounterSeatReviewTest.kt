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
// Historical evidence for the rejected blue-only attempt at 1cbbcf7.
// The live metal contour is protected by CarbonLowerMetalCollarReviewTest.
class CarbonSixCounterSeatReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun point(x:Int,y:Int,side:Int)=Pair(500f+((x+.5f)*1000/side-500f)/1.05f,500f+((y+.5f)*1000/side-500f)/1.05f)
    @Test fun onlyLowerTrackTangentChangesAcrossSizesBatteryBandsAndLume() {
        val old=BaselineBeforeSixSeatCarbonDialArt();val current=BaselineBeforeMetalCollarCarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray()))for(lume in listOf(0f,1f)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band,lumeAlpha=lume)
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        val (dx,dy)=point(x,y,side)
                        // Fixed bounds include AA and 256->240 resampling only;
                        // every included pixel elsewhere must remain exact.
                        val lowerSeat=dx in 436f..564f && dy in 766f..796f
                        if(!lowerSeat) {
                            protected++;assertEquals("Unrelated pixel $x,$y/$side/$band/$lume",a.getPixel(x,y),b.getPixel(x,y))
                        }
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.995f)
                    assertTrue("Lower bezel seat must visibly change",changed>3)
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
    @Test fun noNewCentreBridgeAndAllCounterInteriorsRemainExact() {
        val old=BaselineBeforeSixSeatCarbonDialArt();val current=BaselineBeforeMetalCollarCarbonDialArt()
        val a=bitmap(1000);val b=bitmap(1000)
        try {
            old.draw(Canvas(a),bounds(1000),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
            current.draw(Canvas(b),bounds(1000),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
            val framework=Region().apply {setPath(CarbonBridgeGeometry.frame(),Region(0,0,1000,1000))}
            for(y in 568..590)for(x in 480..520)assertFalse("Centre must never connect to six",framework.contains(x,y))
            for(y in 0 until 1000)for(x in 0 until 1000) {
                val (dx,dy)=point(x,y,1000)
                val centreGap=dx in 475f..525f && dy in 568f..590f
                val counter=listOf(350f to 465f,650f to 465f,500f to 693f).any {(cx,cy)->(dx-cx)*(dx-cx)+(dy-cy)*(dy-cy)<81f*81f}
                if(centreGap || counter)assertEquals("Counter or open centre gap changed $x,$y",a.getPixel(x,y),b.getPixel(x,y))
            }
        }finally{a.recycle();b.recycle();old.release();current.release()}
    }
    @Test fun lowerTrackNoLongerHasTheBlackClearanceNotch() {
        val old=BaselineBeforeSixSeatCarbonDialArt();val current=BaselineBeforeMetalCollarCarbonDialArt()
        val a=bitmap(1000);val b=bitmap(1000)
        try {
            old.draw(Canvas(a),bounds(1000),epoch,motion=false)
            current.draw(Canvas(b),bounds(1000),epoch,motion=false)
            var restored=0
            for(y in 790..800)for(x in 490..510) {
                val before=a.getPixel(x,y);val after=b.getPixel(x,y)
                val bright=Color.green(after)>140 && Color.blue(after)>140
                if(bright && Color.green(before)<100 && Color.blue(before)<100)restored++
            }
            assertTrue("The lower blue/lume track should replace the black tangent notch: $restored",restored>10)
        }finally{a.recycle();b.recycle();old.release();current.release()}
    }
    @Test fun exportNativeSixCounterComparison() {
        val output=System.getenv("CARBON_SIX_SEAT_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs())
        val old=BaselineBeforeSixSeatCarbonDialArt();val current=BaselineBeforeMetalCollarCarbonDialArt()
        fun write(name:String,b:Bitmap)=File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}
        fun compare(a:Bitmap,b:Bitmap,title:String)=Bitmap.createBitmap(a.width+b.width+30,maxOf(a.height,b.height)+80,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);c.drawColor(0xff161b20.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
            c.drawText("BEFORE · 07a51cc",10f,28f,p);c.drawText(title,a.width+20f,28f,p)
            c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null)
        }
        try {
            for(side in listOf(240,360,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    write("before-$side.png",a);write("after-$side.png",b)
                    if(side==720) {
                        val full=compare(a,b,"SIX-O'CLOCK BEZEL SEAT")
                        fun detail(source:Bitmap)=Bitmap.createBitmap(800,700,Bitmap.Config.ARGB_8888).also {
                            Canvas(it).drawBitmap(source,Rect(260,425,460,600),Rect(0,0,800,700),Paint().apply {isFilterBitmap=false})
                        }
                        val da=detail(a);val db=detail(b)
                        val lower=compare(da,db,"LOWER JOIN · 4× NATIVE PIXELS")
                        val sheet=Bitmap.createBitmap(maxOf(full.width,lower.width),full.height+lower.height+20,Bitmap.Config.ARGB_8888)
                        try {
                            val c=Canvas(sheet);c.drawColor(0xff161b20.toInt());c.drawBitmap(full,0f,0f,null);c.drawBitmap(lower,0f,full.height.toFloat(),null)
                            write("Nano-six-counter-bezel-review.png",sheet)
                        }finally{full.recycle();da.recycle();db.recycle();lower.recycle();sheet.recycle()}
                    }
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
}
