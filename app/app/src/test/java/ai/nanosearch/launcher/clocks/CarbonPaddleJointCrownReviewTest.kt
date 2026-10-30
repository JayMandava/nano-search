package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonPaddleJointCrownReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun jointOrCrown(x:Float,y:Float):Boolean {
        val joint=(x in 78f..150f || x in 850f..922f) && (y in 185f..385f || y in 615f..815f)
        return joint || (x in 910f..1000f && y in 398f..602f)
    }
    @Test fun approvedPaddleBodiesAndEntireFaceStayExactOutsideJointsAndCrown() {
        val old=BaselineApprovedPaddleCarbonDialArt();val current=CarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000))for(band in listOf(null,*BatteryBand.entries.toTypedArray())) {
                val a=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
                val b=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
                try {
                    val bounds=RectF(0f,0f,side.toFloat(),side.toFloat())
                    old.draw(Canvas(a),bounds,epoch,motion=false,batteryBand=band)
                    current.draw(Canvas(b),bounds,epoch,motion=false,batteryBand=band)
                    // Protect the carbon face inside the existing silver guard.
                    // The guard's AA edge belongs to the intentionally changed
                    // joint, including the min-256 cache's resampling footprint.
                    val interior=CarbonDialArt::class.java.getDeclaredMethod("tonneau",java.lang.Float.TYPE).apply {isAccessible=true}.invoke(current,16f) as Path
                    interior.transform(Matrix().apply {setScale(side/1000f,side/1000f)})
                    val face=Region().apply {setPath(interior,Region(0,0,side,side))}
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        if(face.contains(x,y) || !jointOrCrown((x+.5f)*1000/side,(y+.5f)*1000/side)) {
                            protected++;assertEquals("Protected approved pixel $x,$y/$side/$band",a.getPixel(x,y),b.getPixel(x,y))
                        }
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.92);assertTrue(changed>side)
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
    @Test fun curvedJointsConnectWithoutTransparentGapsAtAllFourSeats() {
        val current=CarbonDialArt();val b=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888)
        try {
            current.draw(Canvas(b),RectF(0f,0f,1000f,1000f),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
            // At the shoulder's inward bend the old straight inner edge left
            // a gap. Across the new seat every pixel has continuous coverage.
            for(lower in listOf(false,true))for(left in listOf(false,true)) {
                for(y in 250..350)for(x in 880..918) {
                    val xx=if(left)999-x else x;val yy=if(lower)999-y else y
                    assertEquals("Gap at paddle seat $xx,$yy",255,Color.alpha(b.getPixel(xx,yy)))
                }
            }
        }finally{current.release();b.recycle()}
    }
    @Test fun crownHasProportionalOrangeBaseSteppedEndAndCanvasClearance() {
        val current=CarbonDialArt();val b=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888)
        try {
            current.draw(Canvas(b),RectF(0f,0f,1000f,1000f),epoch,motion=false)
            fun orange(color:Int)=Color.red(color)>210 && Color.green(color) in 65..155 && Color.blue(color)<65
            val orangeRows=(390..610).count {y -> (925..980).any {x -> orange(b.getPixel(x,y))}}
            val capRows=(390..610).count {y -> Color.alpha(b.getPixel(990,y))>0}
            assertTrue("Orange base should span about 188 units: $orangeRows",orangeRows in 184..190)
            assertTrue("Outer cylinder must step down in height: $capRows",capRows in 120..148)
            assertTrue(Color.alpha(b.getPixel(997,500))>0)
            for(y in 0 until 1000)assertEquals("Crown clipped at canvas edge",0,Color.alpha(b.getPixel(999,y)))
        }finally{current.release();b.recycle()}
    }
}
