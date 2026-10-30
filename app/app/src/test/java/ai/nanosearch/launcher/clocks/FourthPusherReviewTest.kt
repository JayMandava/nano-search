package ai.nanosearch.launcher.clocks

import android.graphics.*
import android.view.MotionEvent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FourthPusherReviewTest {
    private val epoch=Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private fun bitmap(side:Int)=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
    private fun bounds(side:Int)=RectF(0f,0f,side.toFloat(),side.toFloat())
    private fun allowed(x:Float,y:Float)=((x in 42f..150f || x in 870f..955f) && y in 285f..390f) ||
        ((x in 48f..145f || x in 870f..955f) && y in 605f..755f)
    @Test fun pixelsOutsideFourExplicitButtonRegionsStayExactIncludingBatteryAndDial() {
        val before=BaselineCaseCarbonDialArt();val after=CarbonDialArt()
        try {
            for(side in listOf(240,360,720,1000)) for(band in listOf(null,*BatteryBand.entries.toTypedArray())) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    before.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=band)
                    after.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=band)
                    var protected=0;var changed=0
                    for(y in 0 until side)for(x in 0 until side) {
                        if(!allowed((x+.5f)*1000/side,(y+.5f)*1000/side)) {
                            protected++;assertEquals("Unapproved pixel $x,$y/$side/$band",a.getPixel(x,y),b.getPixel(x,y))
                        }
                        if(a.getPixel(x,y)!=b.getPixel(x,y))changed++
                    }
                    assertTrue(protected>side*side*.88);assertTrue(changed>side/2)
                }finally{a.recycle();b.recycle()}
            }
        }finally{before.release();after.release()}
    }
    @Test fun acceptedBatteryBridgeAndInnerDialStayFrozenWithExactBaselineProvenance() {
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val expected=mapOf("CarbonBatteryArt" to "0679cd1f958c98981d8022b5b65c4610a30dae38b8df89f6cf75239e6c06a713", "CarbonBridgeArt" to "0286c6b3ea818f6b0b41eb3aab7c4d01415c5ee17dcf372973660f33b64411a0", "PhoneBattery" to "597b049a05b8127be4b7d7eb795af819e8841b6e1e4d49db82b630afe632cd4c")
        for((name,hash) in expected)assertEquals("Accepted $name changed",hash,sha(File("src/main/java/ai/nanosearch/launcher/clocks/$name.kt").readBytes()))
        val geometry=File("src/main/java/ai/nanosearch/launcher/clocks/CarbonPusherGeometry.kt").readText()
        val dial=geometry.substringAfter("fun innerDialPath()").substringBefore("fun layout(")
        assertEquals("664e847b679e6ac2aba0bcad32c9b8ecff62852176584abc0faf2725bd789b98",sha(dial.toByteArray(Charsets.UTF_8)))
        val frozen=File("src/test/java/ai/nanosearch/launcher/clocks/BaselineCaseCarbonDialArt.kt").readText()
            .replace("BaselineCaseCarbonDialArt","CarbonDialArt").replace("BaselineCaseCarbonDialGeometry","CarbonDialGeometry")
        assertEquals("bcd5aaca0b7ccd12a6595ff60003aa588d4048133cc49ab9877ebcf23e39863f",sha(frozen.toByteArray(Charsets.UTF_8)))
    }

    @Test fun fourButtonsAndCrownKeep48dpTargetsAndNeverCollideAtSupportedSizes() {
        val controls=listOf(CarbonPusherAction.REV,CarbonPusherAction.START_PAUSE,CarbonPusherAction.SPLIT_RELEASE,CarbonPusherAction.RESET,CarbonPusherAction.LUME)
        for((side,density) in listOf(240 to 1f,360 to 1f,720 to 3f,1080 to 3f)) {
            val layout=CarbonPusherGeometry.layout(side.toFloat(),side.toFloat(),density)
            for(action in controls) {
                val b=layout.boundsFor(action)
                assertTrue(b.width()>=48*density-.01f);assertTrue(b.height()>=48*density-.01f)
                assertEquals(action,layout.hitTest(b.centerX(),b.centerY()))
                for(other in controls)if(other!=action)assertFalse("$action overlaps $other",RectF.intersects(b,layout.boundsFor(other)))
            }
            for((action,x,y) in listOf(Triple(CarbonPusherAction.REV,85f,339f),Triple(CarbonPusherAction.START_PAUSE,905f,339f),Triple(CarbonPusherAction.SPLIT_RELEASE,85f,691.5f),Triple(CarbonPusherAction.RESET,905f,668f),Triple(CarbonPusherAction.LUME,950f,500f))) {
                assertEquals(action,layout.hitTest(x*side/1000,y*side/1000))
            }
        }
    }
    @Test fun wallpaperTapRequiresExplicitSinglePointerDownAndUpOnRev() {
        val layout=CarbonPusherGeometry.layout(360f,360f,1f);val rect=layout.boundsFor(CarbonPusherAction.REV)
        val tracker=CarbonRevTap(8f)
        fun touch(action:Int,x:Float=rect.centerX(),y:Float=rect.centerY(),enabled:Boolean=true,time:Long=10L):Boolean {
            val event=MotionEvent.obtain(0,time,action,x,y,0)
            return try {tracker.touch(event,layout,0f,0f,enabled)}finally{event.recycle()}
        }
        assertFalse(touch(MotionEvent.ACTION_UP))
        assertFalse(touch(MotionEvent.ACTION_DOWN));assertTrue(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN);touch(MotionEvent.ACTION_MOVE,rect.centerX()+20f);assertFalse(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN);touch(MotionEvent.ACTION_CANCEL);assertFalse(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN);assertFalse(touch(MotionEvent.ACTION_UP,enabled=false))
        touch(MotionEvent.ACTION_DOWN);assertFalse(touch(MotionEvent.ACTION_UP,time=10L+android.view.ViewConfiguration.getLongPressTimeout()))
        touch(MotionEvent.ACTION_DOWN);assertFalse(touch(MotionEvent.ACTION_UP,time=9L))
        touch(MotionEvent.ACTION_DOWN);touch(MotionEvent.ACTION_POINTER_DOWN);assertFalse(touch(MotionEvent.ACTION_UP))
        assertFalse(touch(MotionEvent.ACTION_DOWN,180f,180f));assertFalse(touch(MotionEvent.ACTION_UP,180f,180f))
    }
    @Test fun homeRevIsExplicitAccessibleAndStopsWhenHiddenOrDetached() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        val host=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val view=CarbonHomeClockView(host.get(),"test-fourth-rev")
        val fake=CarbonRevSoundTest.Fake();var time=1000L
        val sound=CarbonRevSound({fake},{time})
        CarbonHomeClockView::class.java.getDeclaredField("revSound").apply {isAccessible=true}.set(view,sound)
        try {
            host.get().setContentView(view);host.visible();host.get().window.decorView.visibility=android.view.View.VISIBLE
            val attach=org.robolectric.util.ReflectionHelpers.getField<Any>(view,"mAttachInfo")
            org.robolectric.util.ReflectionHelpers.setField(attach,"mWindowVisibility",android.view.View.VISIBLE)
            view.layout(0,0,360,360)
            org.robolectric.Shadows.shadowOf(view).setGlobalVisibleRect(Rect(0,0,360,360))
            view.onVisibilityAggregated(true)
            assertEquals(0,fake.starts)
            assertTrue("Visible home clock accepts explicit rev",view.performPusherAction(CarbonPusherAction.REV))
            assertFalse(view.performPusherAction(CarbonPusherAction.REV));assertEquals(1,fake.starts)
            val node=view.accessibilityNodeProvider.createAccessibilityNodeInfo(CarbonPusherAction.REV.ordinal+1)
            assertTrue(requireNotNull(node).contentDescription.toString().contains("Play original supercar rev"))
            view.onVisibilityAggregated(false);assertEquals(1,fake.releases);assertFalse(sound.playing)
            time+=3000;view.onVisibilityAggregated(true);assertTrue(view.performPusherAction(CarbonPusherAction.REV))
            host.pause().stop().destroy();assertEquals(2,fake.releases);assertFalse(sound.playing)
        }finally{sound.stop()}
    }

    @Test fun wallpaperRevOnlyFollowsItsExplicitButtonAndStopsWhenHidden() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        val prefs=app.getSharedPreferences("nano",0)
        prefs.edit().putString("lockclock_face",ClockFaces.CARBON_ID).putString("lockclock_bg","black").commit()
        val host=org.robolectric.Robolectric.buildService(ClockWallpaperService::class.java).create()
        val engine=host.get().onCreateEngine();val fake=CarbonRevSoundTest.Fake();val sound=CarbonRevSound({fake},{1000})
        engine.javaClass.getDeclaredField("revSound").apply {isAccessible=true}.set(engine,sound)
        val b=bitmap(480)
        fun touch(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(0,10,action,x,y,0)
            try{engine.onTouchEvent(event)}finally{event.recycle()}
        }
        try {
            engine.onVisibilityChanged(true)
            engine.javaClass.getDeclaredField("w").apply {isAccessible=true}.setInt(engine,480)
            engine.javaClass.getDeclaredField("h").apply {isAccessible=true}.setInt(engine,480)
            engine.javaClass.getDeclaredMethod("render",Canvas::class.java,java.lang.Long.TYPE).apply {isAccessible=true}.invoke(engine,Canvas(b),epoch)
            assertEquals(0,fake.starts)
            val rect=engine.javaClass.getDeclaredField("dialBounds").apply {isAccessible=true}.get(engine) as RectF
            val x=rect.left+rect.width()*.085f;val y=rect.top+rect.height()*.339f
            touch(MotionEvent.ACTION_DOWN,240f,240f);touch(MotionEvent.ACTION_UP,240f,240f);assertEquals(0,fake.starts)
            touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_UP,x,y);assertEquals(1,fake.starts)
            engine.onVisibilityChanged(false);assertEquals(1,fake.releases);assertFalse(sound.playing)
            touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_UP,x,y);assertEquals(1,fake.starts)
            engine.onDestroy();assertEquals(1,fake.releases)
        }finally{sound.stop();b.recycle();host.destroy();prefs.edit().clear().commit()}
    }

    @Test fun exportOriginalNativeBeforeAfterFullFaceAndButtons() {
        val output=System.getenv("CARBON_FOURTH_PUSHER_OUTPUT").orEmpty();assumeTrue(output.isNotEmpty())
        val dir=File(output);check(dir.mkdirs());val old=BaselineCaseCarbonDialArt();val current=CarbonDialArt()
        fun write(name:String,b:Bitmap){File(dir,name).outputStream().use {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
        fun compare(a:Bitmap,b:Bitmap,title:String):Bitmap=Bitmap.createBitmap(a.width+b.width+30,a.height+80,Bitmap.Config.ARGB_8888).also {
            val c=Canvas(it);c.drawColor(0xff161b20.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
            c.drawText("BEFORE · 5449ca3",10f,28f,p);c.drawText(title,a.width+20f,28f,p)
            c.drawBitmap(a,10f,44f,null);c.drawBitmap(b,a.width+20f,44f,null)
        }
        try {
            for(side in listOf(240,360,720)) {
                val a=bitmap(side);val b=bitmap(side)
                try {
                    Canvas(a).drawColor(0xff161b20.toInt());Canvas(b).drawColor(0xff161b20.toInt())
                    old.draw(Canvas(a),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    current.draw(Canvas(b),bounds(side),epoch,motion=false,batteryBand=BatteryBand.MEDIUM)
                    write("before-$side.png",a);write("after-$side.png",b)
                    if(side==720) {
                        val full=compare(a,b,"FOUR PUSHERS · REVIEW")
                        val details=mutableListOf<Bitmap>()
                        try {
                            write("comparison-full.png",full)
                            for((name,r) in listOf("left" to Rect(30,180,120,550),"right" to Rect(610,180,700,550))) {
                                fun crop(source:Bitmap)=Bitmap.createBitmap(r.width()*3,r.height()*3,Bitmap.Config.ARGB_8888).also {Canvas(it).drawBitmap(source,r,Rect(0,0,it.width,it.height),Paint().apply {isFilterBitmap=false})}
                                val ca=crop(a);val cb=crop(b)
                                try {details+=compare(ca,cb,name.uppercase()+" · 3× NATIVE PIXELS")}finally{ca.recycle();cb.recycle()}
                            }
                            val sheet=Bitmap.createBitmap(full.width,full.height+details.maxOf {it.height}+20,Bitmap.Config.ARGB_8888)
                            try {
                                val c=Canvas(sheet);c.drawColor(0xff161b20.toInt());c.drawBitmap(full,0f,0f,null)
                                c.drawBitmap(details[0],0f,full.height.toFloat(),null);c.drawBitmap(details[1],details[0].width+10f,full.height.toFloat(),null)
                                write("Nano-four-pusher-review.png",sheet)
                            }finally{sheet.recycle()}
                        }finally{full.recycle();details.forEach(Bitmap::recycle)}
                    }
                }finally{a.recycle();b.recycle()}
            }
        }finally{old.release();current.release()}
    }
}
