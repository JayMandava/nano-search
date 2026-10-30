package ai.nanosearch.launcher.clocks

import android.graphics.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Canvas evidence, never an Android device or phone-performance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Historical bridge/battery revision proof against frozen 11166bf sources.
// The accepted case/sector revision is checked directly by ReferenceReviewCandidateTest.
class CarbonRefinement084Test {
    private val epoch = Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side: Int) = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private fun bounds(side: Int) = RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun canvas(bitmap: Bitmap) = Canvas(bitmap).apply {
        scale(bitmap.width/1000f,bitmap.height/1000f)
        scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
    }
    // Path union can retessellate existing bevel edges. Limit those exact-pixel changes
    // to the frozen prior material, never a mask inferred from the revised renderer.
    private val priorMaterial by lazy {
        val path=Path(); Paint().apply { style=Paint.Style.FILL_AND_STROKE;strokeWidth=20f }.getFillPath(Baseline083CarbonBridgeGeometry.frame(),path)
        Region().apply {setPath(path,Region(0,0,1000,1000))}
    }
    private fun allowed(x: Float, y: Float): Boolean =
        priorMaterial.contains(x.toInt(),y.toInt()) ||
        ((x in 373f..463f || x in 537f..627f) && y in 216f..389f) ||
        ((x in 299f..444f || x in 556f..701f) && y in 548f..797f) ||
        (x in 617f..717f && y in 647f..753f)

    @Test fun changesStayInsideFourSparContoursAndBatteryDetailAtAllSizes() {
        val old = Baseline083CarbonDialArt(); val current = Baseline084CarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000)) for(lume in listOf(0f,1f)) {
                val a=bitmap(side); val b=bitmap(side)
                try {
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,lumeAlpha=lume)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,lumeAlpha=lume)
                    var changed=0; var protected=0;var interiors=0
                    for(y in 0 until side) for(x in 0 until side) {
                        val dx=500f+((x+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                        val dy=500f+((y+.5f)*1000f/side-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                        if(!allowed(dx,dy)) { protected++; assertEquals("Unauthorized pixel $x,$y/$side",a.getPixel(x,y),b.getPixel(x,y)) }
                        val counter=listOf(350f to 465f,650f to 465f,500f to 693f).any { (cx,cy) -> (dx-cx)*(dx-cx)+(dy-cy)*(dy-cy)<79f*79f }
                        val date=dx in 346f..378f && dy in 276f..350f
                        if(counter || date) {interiors++; assertEquals("Counter/date changed at $x,$y/$side",a.getPixel(x,y),b.getPixel(x,y))}
                        if(a.getPixel(x,y)!=b.getPixel(x,y)) changed++
                    }
                    assertTrue(changed>side/2); assertTrue(protected>side*side*.80f);assertTrue(interiors>side*side/30)
                } finally { a.recycle();b.recycle() }
            }
        } finally { old.release();current.release() }
    }

    @Test fun completeBaseLayerIncludingRearPlatesAndEngravingRemainsExact() {
        val old=Baseline083CarbonDialArt();val current=Baseline084CarbonDialArt()
        try {
            for(side in listOf(240,720,1000)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    for((art,target) in listOf(old to a,current to b)) {
                        val c=Canvas(target);c.scale(side/1000f,side/1000f)
                        art.javaClass.getDeclaredMethod("underlay",Canvas::class.java).apply {isAccessible=true}.invoke(art,c)
                    }
                    assertTrue("Every base pixel must remain exact, including all rear plates and cleared engraving",a.sameAs(b))
                } finally {a.recycle();b.recycle()}
            }
        } finally {old.release();current.release()}
    }

    @Test fun batteryChangesRenderImmediatelyWithoutChangingOtherDialPixels() {
        val renderer=Baseline084CarbonClockFaceRenderer()
        val images=mutableListOf<Bitmap>()
        try {
            for(level in listOf<Int?>(null,0,20,21,70,71,100,-1,101)) {
                renderer.onBatteryChanged(level)
                val b=bitmap(720);images+=b;renderer.draw(Canvas(b),bounds(720),epoch,false)
            }
            assertTrue(images[1].sameAs(images[2])); assertTrue(images[3].sameAs(images[4])); assertTrue(images[5].sameAs(images[6]))
            assertTrue(images[0].sameAs(images[7])); assertTrue(images[0].sameAs(images[8]))
            // The medium pointer must not obscure the M label.
            var labelSamples=0
            for(y in 0 until 720) for(x in 0 until 720) {
                val dx=500f+((x+.5f)*1000f/720-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                val dy=500f+((y+.5f)*1000f/720-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                if(dx in 635f..647f && dy in 677f..692f) {
                    labelSamples++;assertEquals(images[0].getPixel(x,y),images[3].getPixel(x,y))
                }
            }
            assertTrue(labelSamples>50)
            for(index in listOf(1,3,5)) {
                assertFalse(images[0].sameAs(images[index]))
                for(y in 0 until 720) for(x in 0 until 720) {
                    val dx=500f+((x+.5f)*1000f/720-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                    val dy=500f+((y+.5f)*1000f/720-500f)/CarbonDialGeometry.ASSEMBLY_SCALE
                    if(dx !in 617f..717f || dy !in 647f..753f) assertEquals(images[0].getPixel(x,y),images[index].getPixel(x,y))
                }
            }
        } finally { images.forEach(Bitmap::recycle);renderer.release() }
    }

    @Test fun wallpaperStopsBatteryEventsWhenHiddenAndRefreshesOnReturn() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("nano",0)
        prefs.edit().putString("lockclock_face",ClockFaces.CARBON_ID).putString("lockclock_bg","black").commit()
        fun reading(level:Int)=android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
            .putExtra(android.os.BatteryManager.EXTRA_LEVEL,level).putExtra(android.os.BatteryManager.EXTRA_SCALE,100)
            .putExtra(android.os.BatteryManager.EXTRA_PRESENT,true)
        app.sendStickyBroadcast(reading(20))
        val controller=org.robolectric.Robolectric.buildService(ClockWallpaperService::class.java).create()
        val engine=controller.get().onCreateEngine()
        val image=bitmap(360)
        fun observer()=engine.javaClass.getDeclaredField("battery").apply {isAccessible=true}.get(engine) as PhoneBatteryObserver
        fun render() {
            engine.javaClass.getDeclaredField("w").apply {isAccessible=true}.setInt(engine,360)
            engine.javaClass.getDeclaredField("h").apply {isAccessible=true}.setInt(engine,360)
            engine.javaClass.getDeclaredMethod("render",Canvas::class.java,java.lang.Long.TYPE).apply {isAccessible=true}.invoke(engine,Canvas(image),epoch)
        }
        try {
            engine.onVisibilityChanged(true);render()
            assertEquals(20,observer().percent)
            engine.onVisibilityChanged(false)
            app.sendStickyBroadcast(reading(71));org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(20,observer().percent)
            engine.onVisibilityChanged(true);render()
            assertEquals(71,observer().percent)
            engine.onDestroy()
            app.sendBroadcast(reading(0));org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(71,observer().percent)
        } finally {image.recycle();controller.destroy();prefs.edit().clear().commit()}
    }

    @Test fun frozen083FixturesRetainExactIdentifierOnlySourceProvenance() {
        val names=listOf("CarbonDialArt","CarbonDialGeometry","CarbonBridgeArt","CarbonBridgeGeometry","CarbonRearSupportArt","CarbonRearSupportGeometry")
        val expected=mapOf("CarbonDialArt" to "ea1589469d6932a8ca5d002a9645e4bf4c43d100d2473aaa40562a148cbcff21", "CarbonBridgeArt" to "bef33fa0f10c4e195bd270339ed9dad4d80d39d2e5f16aea28cfc07e656cf46e", "CarbonRearSupportArt" to "a72ea5b0dc1023c7752bc0161bb5b96740485ec0860be9a1add44173f4d4445d")
        for((name,hash) in expected) {
            var text=File("src/test/java/ai/nanosearch/launcher/clocks/Baseline083$name.kt").readText()
            names.forEach {text=text.replace("Baseline083$it",it)}
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)})
        }
    }

    @Test fun exportActualBeforeAfterAndBatteryBands() {
        val output=System.getenv("CARBON_084_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs())
        val old=Baseline083CarbonDialArt();val current=Baseline084CarbonDialArt()
        fun write(name:String,b:Bitmap) {File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
        try {
            for(side in listOf(240,360,720)) for(background in listOf("light","dark")) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    val ca=Canvas(a);val cb=Canvas(b)
                    val color=if(background=="light") Color.rgb(235,235,235) else Color.rgb(12,15,18)
                    ca.drawColor(color);cb.drawColor(color)
                    old.draw(ca,bounds(side),epoch,motion=false)
                    current.draw(cb,bounds(side),epoch,motion=false,batteryBand=Baseline084BatteryBand.MEDIUM)
                    write("before-$background-$side.png",a);write("after-$background-$side.png",b)
                    if(side==720) {
                        val sheet=Bitmap.createBitmap(1480,800,Bitmap.Config.ARGB_8888)
                        try {
                            val c=Canvas(sheet);c.drawColor(Color.rgb(25,29,32))
                            val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=Color.WHITE;textSize=24f}
                            c.drawText("BEFORE · v0.8.3",20f,32f,text);c.drawText("REVISED · battery MEDIUM",760f,32f,text)
                            c.drawBitmap(a,10f,48f,null);c.drawBitmap(b,750f,48f,null)
                            write("comparison-$background-720.png",sheet)
                        } finally {sheet.recycle()}
                    }
                } finally {a.recycle();b.recycle()}
            }
            for(band in listOf(null,Baseline084BatteryBand.LOW,Baseline084BatteryBand.MEDIUM,Baseline084BatteryBand.HIGH)) {
                val b=bitmap(720)
                try {current.draw(Canvas(b),bounds(720),epoch,motion=false,batteryBand=band);write("battery-${band?.name ?: "UNKNOWN"}-720.png",b)} finally {b.recycle()}
            }
            val lume=bitmap(720)
            try {current.draw(Canvas(lume),bounds(720),epoch,motion=false,lumeAlpha=1f,batteryBand=Baseline084BatteryBand.MEDIUM);write("after-lume-720.png",lume)} finally {lume.recycle()}
            File(dir,"meaning.txt").writeText("Actual Robolectric API35/native-Skia Canvas renders at 2026-10-15T10:10:30Z. Before source 6c722e3a9cf731354efa29cc24dbe1a3181c0225. Not device screenshots or performance evidence. Light/dark names describe surrounding backgrounds; the RM face material remains dark. Original Library references could not be materialized due network failure.")
        } finally {old.release();current.release()}
    }
}
