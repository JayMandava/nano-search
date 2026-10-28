package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Android Canvas APIs via JVM-native Skia. This is NOT Android emulator/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonBridgeNativeCaptureTest {
    private val epoch = Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
    private data class State(val name: String, val elapsed: Long, val running: Boolean, val motion: Boolean, val lume: Float)
    private val states = listOf(
        State("parked", 0L, false, false, 0f),
        State("running", 12_345L, true, true, 0f),
        State("lume", 0L, false, false, 1f),
    )

    @Test fun frozenPreviousReviewProducesNonemptyDeterministicNativePixels() = inUtc {
        val art = BaselineApprovedCarbonDialArt()
        try {
            art.onTimeChanged()
            val a = bitmap(360); val b = bitmap(360)
            try {
                val bounds = RectF(0f, 0f, 360f, 360f)
                art.draw(Canvas(a), bounds, epoch, motion = false)
                art.release()
                art.draw(Canvas(b), bounds, epoch, motion = false)
                assertTrue("A fresh frozen render must be pixel-identical", a.sameAs(b))
                val pixels = pixels(a)
                assertTrue("Native smoke must render real nontransparent pixels", pixels.count { (it ushr 24) != 0 } > pixels.size / 2)
                assertTrue("Native smoke must not return a flat stub bitmap", pixels.toSet().size > 1_000)
                System.getenv("CARBON_BASELINE_SMOKE_OUTPUT")?.let { path ->
                    val file = File(path)
                    val parent = checkNotNull(file.absoluteFile.parentFile)
                    check(parent.isDirectory || parent.mkdirs())
                    check(!file.exists()) { "Refusing to overwrite smoke evidence: $file" }
                    writePng(file, a)
                }
            } finally { a.recycle(); b.recycle() }
        } finally { art.release() }
    }

    @Test fun exportActualBeforeAfterRenderersAtFixedTimeForHumanReview() = inUtc {
        val destination = System.getenv("CARBON_REVIEW_OUTPUT").orEmpty()
        assumeTrue("PNG export is opt-in with CARBON_REVIEW_OUTPUT", destination.isNotBlank())
        val directory = File(destination)
        check(directory.mkdirs()) { "Use a new output directory; never overwrite render evidence: $directory" }
        val before = BaselineApprovedCarbonDialArt()
        val after = CarbonDialArt()
        val comparisons = JSONArray()
        var supportDetail: JSONObject? = null
        var rearDetail: JSONObject? = null
        var cleanupDetail: JSONObject? = null
        try {
            before.onTimeChanged(); after.onTimeChanged()
            for (side in listOf(240, 360, 720)) for (state in states) {
                val a = bitmap(side); val b = bitmap(side)
                try {
                    val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
                    before.draw(Canvas(a), bounds, epoch, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                    after.draw(Canvas(b), bounds, epoch, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                    val prefix = "${state.name}-${side}px"
                    val oldFile = File(directory, "before-$prefix.png")
                    val newFile = File(directory, "after-$prefix.png")
                    writePng(oldFile, a); writePng(newFile, b)
                    val comparisonFile = if (side == 720 && state.name == "parked") {
                        File(directory, "comparison-parked-720px.png").also { writeComparison(it, a, b) }
                    } else null
                    if(side==720 && state.name=="parked") cleanupDetail=exportCleanupDetail(directory,a,b)
                    val oldPixels = pixels(a); val newPixels = pixels(b)
                    val changed = oldPixels.indices.count { oldPixels[it] != newPixels[it] }
                    assertTrue("Capture must contain an actual refinement, not a relabeled baseline", changed > 0)
                    comparisons.put(JSONObject().put("sidePx", side).put("state", state.name)
                        .put("elapsedMillis", state.elapsed).put("running", state.running)
                        .put("motion", state.motion).put("lumeAlpha", state.lume)
                        .put("changedPixels", changed).put("totalPixels", oldPixels.size)
                        .put("before", record(oldFile)).put("after", record(newFile))
                        .put("comparison", comparisonFile?.let(::record) ?: JSONObject.NULL))
                } finally { a.recycle(); b.recycle() }
            }
            supportDetail = exportSupportDetail(directory)
            rearDetail = exportRearDetail(directory)
        } finally {
            before.release(); after.release()
            File(directory, "metadata.json").writeText(JSONObject()
                .put("renderer", "Robolectric 4.17 / API 35 / native Skia graphics; not an Android emulator or device")
                .put("fixedUtc", "2026-10-15T10:10:30Z").put("epochMillis", epoch)
                .put("baselineRevision", "4058d215b5c08b68b072547e8ff3d544e013aa83")
                .put("sourceRevision", System.getenv("CARBON_REVIEW_REVISION") ?: "working tree; not committed")
                .put("baselineTransform", "Identifier-only renames, protected by exact SHA-256 provenance test")
                .put("comparisons", comparisons)
                .put("supportDetail", supportDetail ?: JSONObject.NULL)
                .put("rearDetail", rearDetail ?: JSONObject.NULL)
                .put("cleanupDetail", cleanupDetail ?: JSONObject.NULL)
                .put("meaning", "Human review evidence, not design approval or device integration validation")
                .toString(2))
        }
    }

    private fun bitmap(side: Int) = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun writePng(file: File, bitmap: Bitmap) = file.outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    /** Labels and untouched render bitmaps are composed by native Android Canvas, not retouched. */
    private fun writeComparison(file: File, before: Bitmap, after: Bitmap, supportOnly: Boolean = false, rearOnly: Boolean = false, cleanupOnly: Boolean = false) {
        val sheet = Bitmap.createBitmap(1488, before.height+128, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(0xff111419.toInt())
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 25f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            canvas.drawText(if (cleanupOnly) "BEFORE · 12 O’CLOCK DETAIL" else if (supportOnly) "PREVIOUS FRONT BRIDGE" else if (rearOnly) "PREVIOUS BASE LAYER" else "APPROVED CANDIDATE · 4058d21", 24f, 42f, text)
            canvas.drawText(if (cleanupOnly) "AFTER · STRAY LINES REMOVED" else if (supportOnly) "UNCHANGED FRONT BRIDGE" else if (rearOnly) "REVISED BASE LAYER" else "REVISED · 12 O’CLOCK CLEANUP", 768f, 42f, text)
            canvas.drawBitmap(before, 12f, 64f, null)
            canvas.drawBitmap(after, 756f, 64f, null)
            text.textSize = 20f; text.color = 0xffb3bec9.toInt()
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            val footer = if(cleanupOnly) "Actual 720 px render crop · 4× nearest-neighbour pixels · JVM native graphics, not a device screenshot"
                else if (supportOnly) "Front bridge is pixel-identical · Rear supports and jewels are not included · No hands or counter faces"
                else if (rearOnly) "Actual base layer · Rear plates and unchanged case · No wheels, front bridge, counters, hands or jewels"
                else "Actual Canvas render · JVM native graphics, NOT a device screenshot · 2026-10-15 10:10:30 UTC"
            canvas.drawText(footer, 24f, sheet.height-24f, text)
            writePng(file, sheet)
        } finally { sheet.recycle() }
    }
    /** Confirms this rear-only revision leaves the approved front support geometry unchanged. */
    private fun exportSupportDetail(directory: File): JSONObject {
        val before=bitmap(720); val after=bitmap(720)
        try {
            fun canvas(bitmap: Bitmap) = Canvas(bitmap).apply {
                scale(.72f,.72f)
                scale(CarbonDialGeometry.ASSEMBLY_SCALE,CarbonDialGeometry.ASSEMBLY_SCALE,500f,500f)
                clipPath(CarbonPusherGeometry.innerDialPath())
            }
            BaselineApprovedCarbonBridgeArt().draw(canvas(before))
            CarbonBridgeArt().draw(canvas(after))
            val oldFile=File(directory,"support-only-before-720px.png")
            val newFile=File(directory,"support-only-after-720px.png")
            val comparison=File(directory,"comparison-support-only-720px.png")
            writePng(oldFile,before);writePng(newFile,after)
            writeComparison(comparison,before,after,supportOnly=true)
            assertTrue("The approved front bridge must remain pixel-identical",before.sameAs(after))
            return JSONObject().put("before",record(oldFile)).put("after",record(newFile))
                .put("comparison",record(comparison))
                .put("meaning","Front bridge painter only, pixel-identical before/after. Rear supports, jewels, hands and counter faces are not included; complete-dial images show the requested revision.")
        } finally {before.recycle();after.recycle()}
    }
    /** Actual cached-base entry points, including former broad plates and their shadows. */
    private fun exportRearDetail(directory:File):JSONObject {
        val previous=BaselineApprovedCarbonDialArt();val current=CarbonDialArt()
        val before=bitmap(720);val after=bitmap(720)
        try {
            fun draw(art:Any,target:Bitmap) {
                val c=Canvas(target);c.scale(.72f,.72f)
                art.javaClass.getDeclaredMethod("underlay",Canvas::class.java).apply {isAccessible=true}.invoke(art,c)
            }
            draw(previous,before);draw(current,after)
            assertFalse("Actual base layers must show the localized engraving cleanup",before.sameAs(after))
            val oldFile=File(directory,"rear-base-before-720px.png")
            val newFile=File(directory,"rear-base-after-720px.png")
            val comparison=File(directory,"comparison-rear-base-720px.png")
            writePng(oldFile,before);writePng(newFile,after)
            writeComparison(comparison,before,after,rearOnly=true)
            return JSONObject().put("before",record(oldFile)).put("after",record(newFile))
                .put("comparison",record(comparison))
                .put("meaning","Actual base layer: only the upper engraving cap differs. Approved rear supports and case remain unchanged. Omits moving wheels and entire front overlay; not a complete watch.")
        } finally {before.recycle();after.recycle();previous.release();current.release()}
    }
    /** Pixel-exact crop of the complete720px renders; enlargement uses no smoothing. */
    private fun exportCleanupDetail(directory:File,before:Bitmap,after:Bitmap):JSONObject {
        fun crop(source:Bitmap):Bitmap {
            val clipped=Bitmap.createBitmap(source,297,164,173,110)
            return try {Bitmap.createScaledBitmap(clipped,692,440,false)} finally {clipped.recycle()}
        }
        val a=crop(before);val b=crop(after)
        try {
            val oldFile=File(directory,"cleanup-12-before-4x.png")
            val newFile=File(directory,"cleanup-12-after-4x.png")
            val comparison=File(directory,"comparison-cleanup-12-4x.png")
            writePng(oldFile,a);writePng(newFile,b);writeComparison(comparison,a,b,cleanupOnly=true)
            assertFalse("The actual12 o’clock crop must contain the requested cleanup",a.sameAs(b))
            return JSONObject().put("before",record(oldFile)).put("after",record(newFile)).put("comparison",record(comparison))
                .put("sourceBitmapPx",720).put("cropPx",JSONArray(listOf(297,164,173,110))).put("nearestNeighbourScale",4)
                .put("meaning","Unretouched crop of the complete dial at fixed time, enlarged4x without smoothing; not a device screenshot.")
        } finally {a.recycle();b.recycle()}
    }
    private fun record(file: File) = JSONObject().put("file", file.name).put("bytes", file.length())
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    private fun inUtc(body: () -> Unit) {
        val previous = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); body() }
        finally { TimeZone.setDefault(previous) }
    }
}
