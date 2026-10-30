package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One shared assembly transform keeps rendered and host-driven clock layers registered. */
object CarbonDialGeometry {
    const val ASSEMBLY_SCALE = 1.05f
    const val SECONDS_CENTER_Y = 702.65f
    const val SECONDS_DIAMETER = 172.2f
    const val DATE_LEFT = 328.85f
    const val DATE_TOP = 255.35f
    const val DATE_WIDTH = 52.5f
    const val DATE_HEIGHT = 97.65f
    const val DATE_TEXT_LEFT = 341.45f
    const val DATE_TEXT_WIDTH = 27.3f
}

/** Original procedural artwork. Mechanical motion is decorative, not a movement simulation. */
class CarbonDialArt {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val calendar = Calendar.getInstance()
    private var base: Bitmap? = null
    private var overlay: Bitmap? = null
    private var cachedSize = 0
    private val orange = Color.rgb(255, 115, 29)
    private val cyan = Color.rgb(53, 185, 219)
    private val yellow = Color.rgb(221, 227, 40)
    private val white = Color.rgb(230, 234, 232)
    private val silver = Color.rgb(168, 175, 177)
    private val gearPath = gearShape()
    private val motionClip = inner()
    private val bezelFootprint=Path().also { path ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=11f;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
        }.getFillPath(inner(),path)
    }
    private val bridgeArt = CarbonBridgeArt()
    private val lowerMetalCollar = CarbonLowerMetalCollarArt()
    private val batteryArt = CarbonBatteryArt()
    private val gearColors = intArrayOf(0xff292e30.toInt(),0xff343a3b.toInt(),0xff292f32.toInt(),0xff393e3f.toInt(),0xff575b58.toInt())
    private val gearArt = arrayOfNulls<Bitmap>(5)
    private val hubFinish = SweepGradient(0f,0f,intArrayOf(0xff707b7c.toInt(),0xffe2e7de.toInt(),0xff555f61.toInt(),0xffc0c9c4.toInt(),0xff707b7c.toInt()),null)

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        epochMillis: Long,
        elapsedMillis: Long = 0L,
        running: Boolean = false,
        motion: Boolean = true,
        hands: Boolean = true,
        showDate: Boolean = true,
        showRunningSeconds: Boolean = true,
        lumeAlpha: Float = 0f,
        batteryBand: BatteryBand? = null,
    ) {
        val size = min(bounds.width(), bounds.height())
        if (size <= 0f) return
        ensureArt(size.toInt().coerceIn(256, 1536))
        val save = canvas.save()
        canvas.translate(bounds.centerX() - size / 2, bounds.centerY() - size / 2)
        canvas.scale(size / 1000f, size / 1000f)
        paint.color = Color.WHITE
        canvas.drawBitmap(base!!, null, RectF(0f, 0f, 1000f, 1000f), paint)
        // Every rate completes an integer number of turns at the one-hour wrap.
        val phase = if (motion) (epochMillis % 3600000L) / 1000f else 0f
        val mechanicsSave=canvas.save()
        canvas.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        canvas.clipPath(motionClip)
        gear(canvas, 446f, 270f, 97f, phase * 6, 0)
        gear(canvas, 624f, 296f, 67f, -phase * 8.7f, 1)
        gear(canvas, 358f, 632f, 83f, -phase * 7, 2)
        gear(canvas, 655f, 639f, 77f, phase * 7.6f, 3)
        gear(canvas, 530f, 554f, 66f, -phase * 9f, 4)
        canvas.restoreToCount(mechanicsSave)
        paint.color = Color.WHITE
        canvas.drawBitmap(overlay!!, null, RectF(0f, 0f, 1000f, 1000f), paint)
        canvas.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        calendar.timeInMillis = epochMillis
        if (showDate) date(canvas, calendar.get(Calendar.DAY_OF_MONTH))
        batteryBand?.let { batteryArt.drawPointer(canvas,it) }
        val angles = CarbonTimeModel.angles(epochMillis, calendar.timeZone)
        if (hands) {
            subHand(canvas, 350f, 465f, (elapsedMillis.coerceAtLeast(0L) % 43200000L) / 43200000f * 360, 58f, orange)
            subHand(canvas, 650f, 465f, (elapsedMillis.coerceAtLeast(0L) % 1800000L) / 1800000f * 360, 59f, orange)
            if (showRunningSeconds) subHand(canvas, 500f, 693f, angles.second, 57f, yellow)
            // Seconds and the parked central chronograph pointer sit beneath local-time hands.
            hand(canvas, 2, if (running || elapsedMillis > 0) (elapsedMillis.coerceAtLeast(0L) % 60000L) / 60000f * 360 else 0f)
            hand(canvas, 0, angles.hour)
            hand(canvas, 1, angles.minute)
            if (lumeAlpha > 0f) drawLume(canvas, angles.hour, angles.minute, lumeAlpha)
            hub(canvas, 500f, 500f, 18f)
        } else {
            subHand(canvas, 350f, 465f, 0f, 58f, orange)
            subHand(canvas, 650f, 465f, 0f, 59f, orange)
            if (showRunningSeconds) subHand(canvas, 500f, 693f, 0f, 57f, yellow)
            hand(canvas, 2, 0f)
        }
        canvas.restoreToCount(save)
    }

    /** The square dial and every hand share the same center for native AnalogClock hosts. */
    fun drawHand(canvas: Canvas, bounds: RectF, kind: Int, angle: Float) {
        val size = min(bounds.width(), bounds.height())
        val save = canvas.save()
        canvas.translate(bounds.centerX() - size / 2, bounds.centerY() - size / 2)
        canvas.scale(size / 1000f, size / 1000f)
        canvas.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        hand(canvas, kind, angle)
        if (kind == 1) hub(canvas, 500f, 500f, 18f)
        canvas.restoreToCount(save)
    }

    /** A host rotates this centered hand inside the separately positioned seconds clock. */
    fun drawSecondsHand(canvas:Canvas,bounds:RectF) {
        val size=min(bounds.width(),bounds.height())
        val save=canvas.save()
        canvas.translate(bounds.centerX()-size/2,bounds.centerY()-size/2)
        canvas.scale(size/164f,size/164f)
        subHand(canvas,82f,82f,0f,57f,yellow)
        canvas.restoreToCount(save)
    }

    /** Refresh cached timezone only on host lifecycle/time callbacks, never through IPC per frame. */
    fun onTimeChanged() { calendar.timeZone = java.util.TimeZone.getDefault() }

    /** The renderer owns its bounded raster caches. Call on its drawing thread after detaching. */
    fun release() {
        base?.recycle(); base = null
        overlay?.recycle(); overlay = null
        gearArt.indices.forEach { index -> gearArt[index]?.recycle(); gearArt[index] = null }
        cachedSize = 0
    }

    private fun ensureArt(size: Int) {
        if (cachedSize == size && base != null && overlay != null) return
        // Five bounded, transparent sprites cover even the largest cached dial resolution.
        for (i in gearArt.indices) if (gearArt[i] == null) {
            gearArt[i] = Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
                val c=Canvas(it);c.translate(160f,160f);c.scale(158f,158f)
                gearArtwork(c,gearColors[i])
            }
        }
        base?.recycle()
        overlay?.recycle()
        cachedSize = size
        base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.scale(size / 1000f, size / 1000f)
            underlay(c)
        }
        overlay = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            val root=c.save()
            c.scale(size / 1000f, size / 1000f)
            c.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
            upper(c)
            c.restoreToCount(root)
            // Bake the foreground plane separately so its blue/tick AA is
            // independent of the counter material underneath it. This is a
            // temporary cache-build image, never an allocation per frame.
            val foreground=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            try {
                val f=Canvas(foreground);f.scale(size/1000f,size/1000f)
                f.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
                f.clipRect(460f,768f,540f,794f);f.clipPath(bezelFootprint)
                track(f)
                paint.shader=null;paint.color=Color.WHITE;paint.style=Paint.Style.FILL
                c.drawBitmap(foreground,0f,0f,paint)
            }finally{foreground.recycle()}
            // Keep the accepted six plane entirely separate. The two side
            // bezels and their existing applied markers get the same foreground
            // priority, with a protected aperture around each counter face.
            val sideForeground=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            try {
                val f=Canvas(sideForeground);f.scale(size/1000f,size/1000f)
                f.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
                f.clipPath(sideBezelForegroundMask())
                fill(f,sideBezelForegroundMask(),0xff1b2024.toInt())
                track(f)
                paint.shader=null;paint.color=Color.WHITE;paint.style=Paint.Style.FILL
                c.drawBitmap(sideForeground,0f,0f,paint)
            }finally{sideForeground.recycle()}
        }
    }

    private fun sideBezelForegroundMask():Path {
        // The full existing dark bezel lip is foreground, not just its cyan stripe.
        val mask=Path().also { Paint().apply {style=Paint.Style.STROKE;strokeWidth=18f}.getFillPath(inner(),it) }
        val contact=CarbonSideBezelSeat.geometry()
        check(mask.op(contact,Path.Op.UNION))
        for((x,angle) in listOf(240f to 270f,760f to 90f)) {
            // Identical applied-marker polygon and 5-unit border from track().
            val marker=Path().apply {
                moveTo(-9f,-7f);lineTo(9f,-7f);lineTo(5f,15f);quadTo(0f,25f,-5f,15f);close()
                transform(Matrix().apply {setRotate(angle);postTranslate(x,500f)})
            }
            val border=Path()
            Paint().apply {style=Paint.Style.FILL_AND_STROKE;strokeWidth=5f;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}.getFillPath(marker,border)
            check(mask.op(border,Path.Op.UNION))
        }
        val joins=Path().apply {addRect(220f,375f,300f,560f,Path.Direction.CW);addRect(700f,375f,780f,560f,Path.Direction.CW)}
        check(mask.op(joins,Path.Op.INTERSECT))
        // Protect the complete front register including its black seat (radius100).
        val faces=Path().apply {addCircle(350f,465f,100.5f,Path.Direction.CW);addCircle(650f,465f,100.5f,Path.Direction.CW)}
        check(mask.op(faces,Path.Op.DIFFERENCE))
        return mask
    }

    private fun tonneau(inset: Float = 0f): Path = Path().apply {
        moveTo(235f,52f)
        cubicTo(365f,45f,635f,45f,765f,52f)
        quadTo(796f,56f,814f,92f);lineTo(865f,191f)
        cubicTo(885f,232f,891f,263f,883f,298f)
        cubicTo(878f,322f,897f,357f,913f,397f)
        cubicTo(927f,447f,927f,553f,913f,603f)
        cubicTo(897f,643f,878f,678f,883f,702f)
        cubicTo(891f,737f,885f,768f,865f,809f)
        lineTo(814f,908f);quadTo(796f,944f,765f,948f)
        cubicTo(635f,955f,365f,955f,235f,948f)
        quadTo(204f,944f,186f,908f);lineTo(135f,809f)
        cubicTo(115f,768f,109f,737f,117f,702f)
        cubicTo(122f,678f,103f,643f,87f,603f)
        cubicTo(73f,553f,73f,447f,87f,397f)
        cubicTo(103f,357f,122f,322f,117f,298f)
        cubicTo(109f,263f,115f,232f,135f,191f)
        lineTo(186f,92f);quadTo(204f,56f,235f,52f);close()
        if(inset!=0f) transform(Matrix().apply {setScale(1f-inset/500f,1f-inset/500f,500f,500f)})
    }

    private fun pusherGuard(c: Canvas, lower: Boolean, left: Boolean = false) {
        fun mirror(path: Path)=path.apply {
            if(lower) transform(Matrix().apply {setScale(1f,-1f,500f,500f)})
            if(left) transform(Matrix().apply {setScale(-1f,1f,500f,500f)})
        }
        // Long slanted paddles follow the case shoulders. Their 184-unit length
        // is about one fifth of the 900-unit case, as in the owner's close-up.
        fun paddleOutline()=Path().apply {
            moveTo(865f,196f);lineTo(914f,191f);quadTo(922f,191f,925f,201f)
            lineTo(965f,340f);quadTo(969f,351f,958f,359f)
            lineTo(916f,375f);quadTo(908f,378f,904f,365f)
        }
        val body=mirror(paddleOutline().apply {close()})
        // Keep the approved body path and its rasterization unchanged. A
        // separate lap paints over its former inner outline and fills the gap
        // beneath the case. Drawing it last avoids a seam across the new seat.
        val lap=mirror(polygon(floatArrayOf(840f,196f,865f,196f,885f,250f,906f,350f,904f,365f,916f,375f,870f,365f)))
        val orangePaddle=lower && left
        if(orangePaddle) {
            fill(c,body,orange);stroke(c,body,1.5f,0xffa34914.toInt())
            fill(c,lap,orange)
        } else {
            gradient(c,body,865f,196f,965f,359f,intArrayOf(0xff354044.toInt(),0xff20292c.toInt(),0xff303b3e.toInt()))
            stroke(c,body,1.5f,0xff536063.toInt())
            gradient(c,lap,865f,196f,965f,359f,intArrayOf(0xff354044.toInt(),0xff20292c.toInt(),0xff303b3e.toInt()))
        }
        val joint=mirror(Path().apply {
            // The identical two Bezier segments used by tonneau(), not a
            // straight approximation. The case paints over the inner half.
            moveTo(865f,191f)
            cubicTo(885f,232f,891f,263f,883f,298f)
            cubicTo(878f,322f,897f,357f,913f,397f)
        })
        val seated=c.save()
        val seat=Path(body).apply {op(lap,Path.Op.UNION)}
        c.clipPath(seat)
        stroke(c,joint,8f,0xffaebcbb.toInt())
        c.restoreToCount(seated)
    }

    private fun inner(): Path = CarbonPusherGeometry.innerDialPath()

    private fun faceRim(scaleX:Float,scaleY:Float):Path = inner().apply {
        transform(Matrix().apply{setScale(scaleX,scaleY,500f,500f)})
    }

    private fun underlay(c: Canvas) {
        // Pusher guards and crown are part of the drawing, not touch targets.
        pusherGuard(c,false);pusherGuard(c,true);pusherGuard(c,false,left=true);pusherGuard(c,true,left=true)
        // 188 / 900 = 20.9% of case height. Keep the stepped cylindrical end
        // inside the fixed 1000-unit square; the accepted case leaves 77 units.
        round(c, 895f, 406f, 982f, 594f, 24f, orange)
        for (i in 0..7) line(c, 919f + i * 8, 417f, 919f + i * 8, 583f, 3f, 0xffa34914.toInt())
        round(c, 967f, 427f, 987f, 573f, 7f, 0xff7d898b.toInt())
        round(c, 975f, 429f, 988f, 571f, 5f, 0xffb5bfbe.toInt())
        oval(c, 975f, 427f, 998f, 573f, 0xffd1d8d4.toInt())
        oval(c, 981f, 440f, 996f, 560f, 0xff465155.toInt())
        val case = tonneau()
        gradient(c, case, 80f, 150f, 940f, 820f, intArrayOf(0xfff1f3ef.toInt(), 0xff646c70.toInt(), 0xffcbd0cf.toInt(), 0xff373c40.toInt(), 0xffe6e8e3.toInt()))
        stroke(c, case, 2f, 0xffe1e5e4.toInt())
        val carbon = tonneau(12f)
        fill(c, carbon, 0xff25282a.toInt())
        val save = c.save()
        c.clipPath(carbon)
        // Deterministic laminated-carbon contours, generated independently of reference pixels.
        for (i in 0..430) {
            val y = i * 2.5f - 20
            val path = Path()
            for (j in 0..110) {
                val x = j * 9.5f - 20
                val dy = sin(x / 71f + i * .034f) * (10+sin(i*.13f)*7) + sin(x / 27f + i * .08f) * 3.7f + sin(x / 143f + i * .19f) * 14 + sin(x/13f+i*.064f)*2.2f
                if (j == 0) path.moveTo(x, y + dy) else path.lineTo(x, y + dy)
            }
            val shade=(39+((sin(i*2.17)*.5+.5)*42)).toInt()
            stroke(c, path, .6f+(i%5)*.35f, Color.rgb(shade,shade+2,shade+2))
        }
        for(i in 0..23) {
            val lamina=Path()
            for(j in 0..100) {
                val x=j*10f
                val y=i*43f+sin(x/94f+i*.64f)*10+sin(x/31f+i*.77f)*4
                if(j==0)lamina.moveTo(x,y) else lamina.lineTo(x,y)
            }
            stroke(c,lamina,4+noise(i,11)*9,0xff181e20.toInt())
            val ridge=c.save();c.translate(0f,4f);stroke(c,lamina,1.1f,0xff545b59.toInt());c.restoreToCount(ridge)
        }
        // Fine mottled resin and irregular fibres are baked once, never regenerated per frame.
        for(i in 0 until 23500) {
            val x=noise(i,1)*1000f;val y=noise(i,2)*1000f
            val shade=(28+noise(i,3)*72).toInt()
            line(c,x,y,x+1+noise(i,4)*6,y+noise(i,5)*1.6f,.35f+noise(i,6)*.6f,Color.argb(95,shade,shade,shade))
        }
        c.restoreToCount(save)
        // Separate polished lip, satin caseband and carbon insert instead of one flat outline.
        stroke(c,tonneau(3f),1.3f,0xffeef1e9.toInt())
        stroke(c,tonneau(7f),2f,0xff626e72.toInt())
        stroke(c,tonneau(11f),1.1f,0xffc8cfca.toInt())
        val sideReflection=Path().apply{moveTo(861f,213f);cubicTo(890f,265f,868f,300f,885f,343f);cubicTo(924f,442f,924f,558f,885f,657f);cubicTo(868f,700f,890f,735f,861f,787f)}
        stroke(c,sideReflection,2.3f,0x997c898d.toInt())
        val faceSave=c.save()
        c.scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
        val rim = faceRim(1.17f,1.13f)
        fill(c, rim, 0xff050708.toInt())
        stroke(c, rim, 5f, 0xff6d7372.toInt())
        val orangeRim = faceRim(1.12f,1.09f)
        fill(c, orangeRim, 0xff101315.toInt())
        stroke(c, orangeRim, 4.5f, orange)
        val dial = inner()
        fill(c, dial, 0xff080b0e.toInt())
        stroke(c, dial, 18f, 0xff1b2024.toInt())
        val save2 = c.save()
        c.clipPath(dial)
        // Four matched, mirrored window frames replace the independently shaped rear plates.
        bridgeArt.drawRear(c)
        // Engine-turned arcs under the central train read as curved machined plates.
        // Keep their upper cap out of the open twelve-o'clock bay: those detached lines
        // read as leftover marks. The clip ends behind the opaque side counters, while
        // all lower engraving remains unchanged.
        val engravingSave = c.save()
        c.clipRect(0f, 390f, 1000f, 1000f)
        // The former plate painter left BUTT caps here. Preserve that explicit state so
        // native oval-seam antialiasing cannot change unrelated engraved arc pixels.
        paint.strokeCap = Paint.Cap.BUTT
        for(i in 0..10) ovalStroke(c,362f-i*3,329f-i*3,660f+i*3,648f+i*3,.65f,if(i%3==0)0xff3d4648.toInt() else 0xff1f282c.toInt())
        c.restoreToCount(engravingSave)
        for ((x,y) in arrayOf(280f to 245f, 715f to 268f, 299f to 760f, 700f to 734f, 478f to 318f)) screw(c,x,y,7f)
        c.restoreToCount(save2)
        c.restoreToCount(faceSave)
        // Twelve spline fasteners follow the case outline.
        for ((x, y) in arrayOf(245f to 92f, 388f to 75f, 612f to 75f, 755f to 92f, 860f to 285f, 884f to 500f, 860f to 715f, 755f to 908f, 612f to 925f, 388f to 925f, 245f to 908f, 140f to 715f)) screw(c, x, y, 18f)
    }

    private fun upper(c: Canvas) {
        val save = c.save()
        c.clipPath(inner())
        // The side-register framework and separate lower collar sit below counters/hands.
        val oldCollar=c.save()
        lowerMetalCollar.clipOldLowerArc(c)
        bridgeArt.draw(c)
        c.restoreToCount(oldCollar)
        lowerMetalCollar.draw(c)
        for ((x,y) in arrayOf(594f to 234f)) {
            oval(c,x-7,y-9,x+7,y+9,0xff381347.toInt())
            ovalStroke(c,x-7,y-9,x+7,y+9,1.7f,0xffa062a8.toInt())
        }
        c.restoreToCount(save)
        track(c)
        subDial(c,350f,465f,95f,orange,12)
        subDial(c,650f,465f,95f,orange,30)
        subDial(c,500f,693f,82f,yellow,60)
        // The side registers clear the blue band. Its lower intersection with
        // six is composited as a foreground plane by ensureArt(), after upper.
        dateAperture(c)
        // Applied orange twelve, outlined with a narrow silver chamfer.
        text(c,"12",500f,317f,77f,0xfff1f1e8.toInt(),true,4f)
        text(c,"12",500f,317f,77f,orange,true)
        // All scale marks and labels share the dedicated indicator geometry.
        batteryArt.drawScale(c)
        // Crystal edge and its subtle reflection.
        stroke(c, faceRim(1.09f,1.09f), 1.2f, 0x887d939d.toInt())
        val glint=Path().apply { moveTo(320f,216f); quadTo(500f,194f,680f,216f) }
        stroke(c,glint,2f,0x99dee9ed.toInt())
    }

    private fun track(c: Canvas) {
        val path=inner()
        stroke(c,path,11f,cyan)
        val measure=PathMeasure(path,true)
        val xy=FloatArray(2)
        val tangent=FloatArray(2)
        for(i in 0 until 240) {
            measure.getPosTan(measure.length*i/240f,xy,tangent)
            val nx=-tangent[1]
            val ny=tangent[0]
            val major=i%20==0
            val len=if(major) 11f else 6f
            line(c,xy[0]-nx*4,xy[1]-ny*4,xy[0]+nx*len,xy[1]+ny*len,if(major)3f else 1.8f,if(major)orange else white)
        }
        // Larger luminous indices sit above the blue track.
        for ((x,y,a) in arrayOf(Triple(500f,230f,0f),Triple(680f,252f,35f),Triple(747f,365f,65f),Triple(760f,500f,90f),Triple(741f,644f,115f),Triple(674f,763f,145f),Triple(500f,780f,180f),Triple(326f,763f,215f),Triple(259f,644f,245f),Triple(240f,500f,270f),Triple(253f,365f,295f),Triple(320f,252f,325f))) {
            val s=c.save(); c.translate(x,y); c.rotate(a)
            val p=Path().apply{moveTo(-9f,-7f);lineTo(9f,-7f);lineTo(5f,15f);quadTo(0f,25f,-5f,15f);close()}
            fill(c,p,0xff101416.toInt());stroke(c,p,5f,0xff171d20.toInt());fill(c,p,white)
            c.restoreToCount(s)
        }
        CarbonScaleLabels.draw(c,white)
    }

    private fun subDial(c:Canvas,x:Float,y:Float,r:Float,color:Int,units:Int) {
        oval(c,x-r-5,y-r-5,x+r+5,y+r+5,0xff050708.toInt())
        paint.color=Color.WHITE
        paint.shader=SweepGradient(x,y,intArrayOf(0xff939a9b.toInt(),0xffe9ece6.toInt(),0xff555e61.toInt(),0xffc5ceca.toInt(),0xff697478.toInt(),0xffe6e9df.toInt(),0xff939a9b.toInt()),null)
        paint.style=Paint.Style.FILL;c.drawCircle(x,y,r+3,paint);paint.shader=null
        for (i in 0..10) ovalStroke(c,x-r+i*.53f,y-r+i*.53f,x+r-i*.53f,y+r-i*.53f,.3f,if(i%2==0)0xffbec6c3.toInt() else 0xff737f80.toInt())
        oval(c,x-r+4,y-r+4,x+r-4,y+r-4,0xff121619.toInt())
        // Open counter centers expose curved plates, a jewel and partial wheel teeth.
        val cavity=Path().apply{addCircle(x,y,r*.57f,Path.Direction.CW)}
        val centerSave=c.save();c.clipPath(cavity)
        oval(c,x-r*.57f,y-r*.57f,x+r*.57f,y+r*.57f,0xff080d10.toInt())
        val mechanism=c.save();c.translate(x,y);c.scale(r/95f,r/95f)
        val wheel=c.save();c.translate(26f,22f);c.scale(36f,36f)
        fill(c,gearPath,0xff555f5d.toInt());stroke(c,gearPath,.025f,0xffa4aea7.toInt())
        oval(c,-.74f,-.74f,.74f,.74f,0xff11191b.toInt())
        for(i in 0..4){c.rotate(72f);line(c,0f,.15f,0f,.73f,.12f,0xff5a6562.toInt())}
        c.restoreToCount(wheel)
        plate(c,floatArrayOf(-57f,-45f, -15f,-55f, 32f,-30f, 26f,-14f, 4f,-7f, -5f,28f, -38f,47f, -57f,36f),arrayOf(floatArrayOf(-40f,-24f,-20f,-33f,4f,-20f,-12f,12f,-35f,23f)),false)
        plate(c,floatArrayOf(-40f,33f,-12f,17f,5f,23f,29f,11f,53f,20f,53f,42f,14f,46f,-19f,58f),emptyArray(),false)
        screw(c,-22f,-29f,8f)
        oval(c,15f,-31f,23f,-23f,0xff511d4f.toInt())
        ovalStroke(c,15f,-31f,23f,-23f,.8f,0xffb690b0.toInt())
        c.restoreToCount(mechanism);c.restoreToCount(centerSave)
        for(i in 0 until 60) {
            val a=Math.toRadians(i*6.0)
            val outer=r-13
            val inner=outer-if(i%5==0) 12 else 5
            line(c,x+sin(a).toFloat()*inner,y-cos(a).toFloat()*inner,x+sin(a).toFloat()*outer,y-cos(a).toFloat()*outer,if(i%5==0)2.7f else 1.5f,white)
        }
        for(i in 0 until 6) {
            val a=Math.toRadians(i*60.0)
            val value=if(i==0) units else i*units/6
            val tx=x+sin(a).toFloat()*(r-28)
            val ty=y-cos(a).toFloat()*(r-28)+6
            text(c,value.toString(),tx,ty,19f,color,true)
        }
    }

    private fun date(c:Canvas,day:Int) {
        val digits=day.toString().padStart(2,'0')
        text(c,digits.substring(0,1),362f,311f,34f,0xffc8d1d0.toInt(),true)
        text(c,digits.substring(1),362f,345f,34f,0xffc8d1d0.toInt(),true)
        line(c,345f,317f,379f,317f,1f,0xff535c5d.toInt())
    }

    private fun dateAperture(c:Canvas) {
        round(c,337f,267f,387f,360f,5f,0xffa6aeae.toInt())
        round(c,342f,272f,382f,355f,3f,0xff030608.toInt())
    }

    private fun hand(c:Canvas,kind:Int,angle:Float) {
        val s=c.save();c.rotate(angle,500f,500f)
        if(kind==2) {
            line(c,500f,579f,500f,259f,3f,0xffaeb7b9.toInt())
            val p=polygon(floatArrayOf(500f,243f,510f,283f,506f,317f,494f,317f,490f,283f))
            fill(c,p,cyan);stroke(c,p,1.2f,0xffb7dce7.toInt())
            round(c,496f,537f,504f,578f,4f,cyan)
        } else {
            val tip=if(kind==0)276f else 245f
            val shoulder=if(kind==0)334f else 294f
            val w=if(kind==0)18f else 14f
            val p=polygon(floatArrayOf(500f,tip,500f+w,shoulder,508f,496f,512f,553f,500f,574f,488f,553f,492f,496f,500f-w,shoulder))
            val shadow=c.save();c.translate(2f,4f);fill(c,p,0x99000000.toInt());c.restoreToCount(shadow)
            gradient(c,p,480f,0f,520f,0f,intArrayOf(0xfff4f5ed.toInt(),0xffa1abad.toInt(),0xffe8ece8.toInt()))
            stroke(c,p,1.8f,0xffe2e7e4.toInt())
            val lumen=polygon(floatArrayOf(500f,tip+16,500f+w-5,shoulder-1,500f,shoulder+14,500f-w+5,shoulder-1))
            fill(c,lumen,yellow);stroke(c,lumen,2.6f,0xff333b37.toInt())
            val opening=polygon(floatArrayOf(496f,shoulder+24,504f,shoulder+24,503f,470f,497f,470f))
            fill(c,opening,0xff11181c.toInt())
            round(c,497f,526f,503f,554f,2f,if(kind==0)orange else cyan)
        }
        c.restoreToCount(s)
    }

    /** Light only the applied indices and the two luminous hand inserts, never the case or date. */
    private fun drawLume(c: Canvas, hour: Float, minute: Float, alpha: Float) {
        fun glow(path: Path) {
            val a = alpha.coerceIn(0f, 1f)
            stroke(c, path, 14f, Color.argb((a * 24).toInt(), 133, 255, 211))
            stroke(c, path, 7f, Color.argb((a * 55).toInt(), 156, 255, 214))
            fill(c, path, Color.argb((a * 225).toInt(), 199, 255, 213))
        }
        for ((x,y,angle) in arrayOf(Triple(500f,230f,0f),Triple(680f,252f,35f),Triple(747f,365f,65f),Triple(760f,500f,90f),Triple(741f,644f,115f),Triple(674f,763f,145f),Triple(500f,780f,180f),Triple(326f,763f,215f),Triple(259f,644f,245f),Triple(240f,500f,270f),Triple(253f,365f,295f),Triple(320f,252f,325f))) {
            val saved=c.save();c.translate(x,y);c.rotate(angle)
            glow(Path().apply { moveTo(-9f,-7f);lineTo(9f,-7f);lineTo(5f,15f);quadTo(0f,25f,-5f,15f);close() })
            c.restoreToCount(saved)
        }
        for ((kind, angle) in arrayOf(0 to hour, 1 to minute)) {
            val tip=if(kind==0)276f else 245f
            val shoulder=if(kind==0)334f else 294f
            val w=if(kind==0)18f else 14f
            val saved=c.save();c.rotate(angle,500f,500f)
            glow(polygon(floatArrayOf(500f,tip+16,500f+w-5,shoulder-1,500f,shoulder+14,500f-w+5,shoulder-1)))
            c.restoreToCount(saved)
        }
    }

    private fun subHand(c:Canvas,x:Float,y:Float,angle:Float,length:Float,color:Int) {
        val s=c.save();c.translate(x,y);c.rotate(angle)
        val p=polygon(floatArrayOf(0f,-length,5f,-length+16,2.5f,19f,-2.5f,19f,-4f,-length+16))
        fill(c,p,0xff030609.toInt());stroke(c,p,2f,0xff080b0c.toInt());fill(c,p,color)
        hub(c,0f,0f,4f);c.restoreToCount(s)
    }

    private fun plate(c:Canvas,outline:FloatArray,windows:Array<FloatArray>,bright:Boolean=true) {
        val path=polygon(outline).apply {
            fillType=Path.FillType.EVEN_ODD
            for(window in windows)addPath(polygon(window))
        }
        val shadow=c.save();c.translate(3f,4f);fill(c,path,0xee000000.toInt());c.restoreToCount(shadow)
        val edgeColors=if(bright)intArrayOf(0xffdce1d9.toInt(),0xff6d797b.toInt(),0xffe6e9e1.toInt(),0xff465459.toInt()) else intArrayOf(0xff64716f.toInt(),0xff1b2429.toInt(),0xff75817c.toInt(),0xff293439.toInt())
        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=if(bright)4.2f else 2.1f
        paint.shader=LinearGradient(250f,200f,750f,790f,edgeColors,null,Shader.TileMode.CLAMP)
        c.drawPath(path,paint);paint.shader=null;paint.style=Paint.Style.FILL
        gradient(c,path,250f,160f,730f,820f,if(bright)intArrayOf(0xff929c99.toInt(),0xff586569.toInt(),0xffadb5ae.toInt(),0xff424e53.toInt()) else intArrayOf(0xff303b3d.toInt(),0xff141c21.toInt(),0xff3c494a.toInt(),0xff202c30.toInt()))
        val b=RectF();path.computeBounds(b,true)
        val texture=c.save();c.clipPath(path)
        val count=(b.height()/1.1f).toInt().coerceAtMost(700)
        for(i in 0..count)line(c,b.left,b.top+i*1.1f,b.right,b.top+i*1.1f-3,.22f,if(i%3==0)0x447f8b88 else 0x44232f34)
        c.restoreToCount(texture)
        // Thin directional highlights describe the upper machined edge.
        for(i in outline.indices step 2) {
            val j=(i+2)%outline.size
            if(outline[j]>=outline[i])line(c,outline[i],outline[i+1]-.6f,outline[j],outline[j+1]-.6f,.65f,if(bright)0xffc7d0c8.toInt() else 0xff66736f.toInt())
        }
    }

    private fun gearShape():Path = Path().apply {
        for(i in 0 until 144) {
            val a=i*Math.PI*2/144
            val r=if(i%4 in 1..2) 1f else .925f
            val x=cos(a).toFloat()*r;val y=sin(a).toFloat()*r
            if(i==0) moveTo(x,y) else lineTo(x,y)
        };close()
    }

    private fun gear(c:Canvas,x:Float,y:Float,r:Float,angle:Float,index:Int) {
        val s=c.save();c.translate(x,y);c.rotate(angle);c.scale(r/158f,r/158f)
        paint.color=Color.WHITE;paint.shader=null;paint.style=Paint.Style.FILL
        c.drawBitmap(gearArt[index]!!,-160f,-160f,paint)
        c.restoreToCount(s)
    }

    /** Rasterized once; animation only rotates and composites this finished artwork. */
    private fun gearArtwork(c:Canvas,color:Int) {
        val s=c.save()
        paint.color=Color.WHITE;paint.style=Paint.Style.FILL
        paint.shader=SweepGradient(0f,0f,intArrayOf(color,0xff77807d.toInt(),color,0xff1d262a.toInt(),color),null)
        c.drawPath(gearPath,paint);paint.shader=null
        stroke(c,gearPath,.009f,0xff727d7b.toInt())
        oval(c,-.79f,-.79f,.79f,.79f,0xff080e11.toInt())
        for(i in 0..5) ovalStroke(c,-.94f+i*.024f,-.94f+i*.024f,.94f-i*.024f,.94f-i*.024f,.004f,if(i%2==0)0xff596361.toInt() else 0xff252e30.toInt())
        ovalStroke(c,-.765f,-.765f,.765f,.765f,.014f,0xff56625f.toInt())
        for(i in 0 until 6) {
            c.rotate(60f)
            line(c,0f,.1f,0f,.77f,.12f,color)
            line(c,-.045f,.18f,-.045f,.73f,.009f,0xff78817d.toInt())
            line(c,.047f,.18f,.047f,.73f,.016f,0xff111a1d.toInt())
        }
        paint.color=Color.WHITE;paint.shader=RadialGradient(-.08f,-.1f,.34f,intArrayOf(0xff9ba5a0.toInt(),color,0xff151f22.toInt()),null,Shader.TileMode.CLAMP)
        c.drawCircle(0f,0f,.23f,paint);paint.shader=null
        ovalStroke(c,-.21f,-.21f,.21f,.21f,.008f,0xff7e8985.toInt())
        oval(c,-.065f,-.065f,.065f,.065f,0xff050709.toInt())
        ovalStroke(c,-.068f,-.068f,.068f,.068f,.007f,0xff9da6a0.toInt())
        c.restoreToCount(s)
    }

    private fun screw(c:Canvas,x:Float,y:Float,r:Float) {
        oval(c,x-r-2,y-r-2,x+r+2,y+r+2,0xff080b0d.toInt())
        paint.color=Color.WHITE;paint.style=Paint.Style.FILL
        paint.shader=SweepGradient(x,y,intArrayOf(0xff202729.toInt(),0xff717976.toInt(),0xff252f32.toInt(),0xff545f5e.toInt(),0xff202729.toInt()),null)
        c.drawCircle(x,y,r,paint);paint.shader=null
        ovalStroke(c,x-r,y-r,x+r,y+r,.85f,0xff515d5c.toInt())
        val s=c.save();c.translate(x,y)
        val p=Path()
        for(i in 0 until 24) {
            val a=i*Math.PI*2/24
            val rad=r*(if(i%4==0 || i%4==3).83f else .65f)
            val px=cos(a).toFloat()*rad;val py=sin(a).toFloat()*rad
            if(i==0)p.moveTo(px,py) else p.lineTo(px,py)
        };p.close();fill(c,p,0xff2a3335.toInt())
        line(c,-r*.43f,-r*.12f,r*.43f,r*.12f,1.5f,0xff050809.toInt());c.restoreToCount(s)
    }

    private fun hub(c:Canvas,x:Float,y:Float,r:Float) {
        val save=c.save();c.translate(x,y);c.scale(r,r)
        paint.color=Color.WHITE;paint.style=Paint.Style.FILL
        paint.shader=hubFinish
        c.drawCircle(0f,0f,1f,paint);paint.shader=null
        ovalStroke(c,-1f,-1f,1f,1f,1.2f/r,0xffe5eae6.toInt())
        oval(c,-.6f,-.6f,.6f,.6f,0xff5d686b.toInt())
        oval(c,-2/r,-2/r,2/r,2/r,0xff151d20.toInt())
        c.restoreToCount(save)
    }

    private fun polygon(points:FloatArray)=Path().apply{moveTo(points[0],points[1]);for(i in 2 until points.size step 2)lineTo(points[i],points[i+1]);close()}
    private fun fill(c:Canvas,path:Path,color:Int){paint.shader=null;paint.style=Paint.Style.FILL;paint.color=color;c.drawPath(path,paint)}
    private fun stroke(c:Canvas,path:Path,width:Float,color:Int){paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND;paint.color=color;c.drawPath(path,paint);paint.style=Paint.Style.FILL}
    private fun gradient(c:Canvas,path:Path,x1:Float,y1:Float,x2:Float,y2:Float,colors:IntArray){paint.color=Color.WHITE;paint.shader=LinearGradient(x1,y1,x2,y2,colors,null,Shader.TileMode.CLAMP);paint.style=Paint.Style.FILL;c.drawPath(path,paint);paint.shader=null}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,width:Float,color:Int){paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.strokeCap=Paint.Cap.BUTT;paint.color=color;c.drawLine(x1,y1,x2,y2,paint);paint.style=Paint.Style.FILL}
    private fun oval(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){paint.shader=null;paint.style=Paint.Style.FILL;paint.color=color;c.drawOval(l,t,r,b,paint)}
    private fun ovalStroke(c:Canvas,l:Float,t:Float,r:Float,b:Float,w:Float,color:Int){paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=w;paint.color=color;c.drawOval(l,t,r,b,paint);paint.style=Paint.Style.FILL}
    private fun round(c:Canvas,l:Float,t:Float,r:Float,b:Float,rad:Float,color:Int){paint.shader=null;paint.style=Paint.Style.FILL;paint.color=color;c.drawRoundRect(l,t,r,b,rad,rad,paint)}
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,outline:Float=0f){paint.shader=null;paint.color=color;paint.textSize=size;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif-condensed",if(bold)Typeface.BOLD else Typeface.NORMAL);paint.style=if(outline>0)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=outline;c.drawText(value,x,y,paint);paint.style=Paint.Style.FILL}
    private fun noise(index:Int,seed:Int):Float {
        var x=index*374761393+seed*668265263
        x=(x xor (x ushr 13))*1274126177
        return ((x xor (x ushr 16)) and 0xffff)/65535f
    }
}
