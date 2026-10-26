package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly requested visual evidence, not a golden that silently approves a design change. */
@RunWith(AndroidJUnit4::class)
class CarbonBridgeVisualCaptureTest {
    private data class State(val name: String, val elapsed: Long, val running: Boolean, val motion: Boolean, val lume: Float)

    @Test fun captureNativeBaselineAndRefinementAtIdenticalTimesAndSizes() {
        val output = CarbonCaptureArtifacts.open("native")
        val oldZone = TimeZone.getDefault()
        val before = BaselineCarbonDialArt()
        val after = CarbonDialArt()
        val comparisons = JSONArray()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC")); before.onTimeChanged(); after.onTimeChanged()
            assertEquals(BaselineCarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 0f)
            assertEquals(BaselineCarbonDialGeometry.SECONDS_CENTER_Y, CarbonDialGeometry.SECONDS_CENTER_Y, 0f)
            assertEquals(BaselineCarbonDialGeometry.SECONDS_DIAMETER, CarbonDialGeometry.SECONDS_DIAMETER, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_LEFT, CarbonDialGeometry.DATE_LEFT, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_TOP, CarbonDialGeometry.DATE_TOP, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_WIDTH, CarbonDialGeometry.DATE_WIDTH, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_HEIGHT, CarbonDialGeometry.DATE_HEIGHT, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_TEXT_LEFT, CarbonDialGeometry.DATE_TEXT_LEFT, 0f)
            assertEquals(BaselineCarbonDialGeometry.DATE_TEXT_WIDTH, CarbonDialGeometry.DATE_TEXT_WIDTH, 0f)
            val states = listOf(
                State("parked", 0L, false, false, 0f),
                State("running", 12_345L, true, true, 0f),
                State("lume", 0L, false, false, 1f),
            )
            for (side in listOf(240, 360, 720)) {
                val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
                for (state in states) {
                    val a = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    try {
                        before.draw(Canvas(a), bounds, CarbonCaptureArtifacts.EPOCH, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                        after.draw(Canvas(b), bounds, CarbonCaptureArtifacts.EPOCH, state.elapsed, state.running, state.motion, lumeAlpha = state.lume)
                        val prefix = "${state.name}-${side}px"
                        val first = output.png("before-$prefix.png", a, "native Android Canvas; frozen baseline")
                        val second = output.png("after-$prefix.png", b, "native Android Canvas; current production art")
                        val difference = difference(a, b)
                        comparisons.put(JSONObject().put("sidePx", side).put("state", state.name)
                            .put("epochMillis", CarbonCaptureArtifacts.EPOCH).put("elapsedMillis", state.elapsed)
                            .put("running", state.running).put("motion", state.motion).put("lumeAlpha", state.lume)
                            .put("before", first).put("after", second).put("difference", difference))
                        // This is a capture-integrity guard, not a visual-quality assertion.
                        assertTrue("A refinement capture must not relabel the unchanged baseline", difference.getInt("changedPixels") > 0)
                        if (side == 360 && state.name == "parked") {
                            after.release()
                            val rebuilt = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                            try {
                                after.draw(Canvas(rebuilt), bounds, CarbonCaptureArtifacts.EPOCH, motion = false)
                                assertTrue("Rebuilding native caches must preserve the exact refined composition", b.sameAs(rebuilt))
                            } finally { rebuilt.recycle() }
                        }
                    } finally { a.recycle(); b.recycle() }
                }
            }
        } finally {
            before.release(); after.release(); TimeZone.setDefault(oldZone)
            output.finish(JSONObject().put("fixedUtc", "2026-10-15T10:10:30Z")
                .put("comparisons", comparisons).put("comparisonMeaning", "Pixel differences are evidence for human review, not approval."))
        }
    }

    private fun difference(before: Bitmap, after: Bitmap): JSONObject {
        val width = before.width; val height = before.height
        val a = IntArray(width * height); val b = IntArray(a.size)
        before.getPixels(a, 0, width, 0, 0, width, height); after.getPixels(b, 0, width, 0, 0, width, height)
        var count = 0; var left = width; var top = height; var right = -1; var bottom = -1
        var alphaChanges = 0; var maximumChannelDelta = 0
        for (i in a.indices) if (a[i] != b[i]) {
            count++; val x = i % width; val y = i / width
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            if ((a[i] ushr 24) != (b[i] ushr 24)) alphaChanges++
            for (shift in 0..24 step 8) maximumChannelDelta = maxOf(maximumChannelDelta,
                kotlin.math.abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)))
        }
        return JSONObject().put("changedPixels", count).put("totalPixels", a.size)
            .put("alphaChangedPixels", alphaChanges).put("maximumChannelDelta", maximumChannelDelta)
            .put("changedBoundsInclusive", if (count == 0) JSONObject.NULL else JSONArray(listOf(left, top, right, bottom)))
    }
}

/** Only opt-in, isolated QA builds may write visual evidence. No personal-font build is accepted. */
internal class CarbonCaptureArtifacts private constructor(private val directory: File, private val section: String) {
    private val entries = JSONArray()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val started = System.currentTimeMillis()

    fun png(name: String, bitmap: Bitmap, origin: String, extra: JSONObject = JSONObject()): JSONObject {
        require(name.matches(Regex("[a-zA-Z0-9_-]+\\.png")))
        val file = File(directory, name)
        check(!file.exists()) { "Capture name already exists: $name; choose a new captureRun" }
        FileOutputStream(file).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync() }
        return JSONObject().put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
            .put("sha256", sha256(file)).put("bytes", file.length()).put("origin", origin)
            .put("capturedAtEpochMillis", System.currentTimeMillis()).put("details", extra).also { entries.put(it) }
    }

    fun finish(details: JSONObject) {
        val metrics = context.resources.displayMetrics
        val metadata = JSONObject().put("section", section).put("package", context.packageName)
            .put("sourceRevision", InstrumentationRegistry.getArguments().getString("captureRevision"))
            .put("baselineRevision", BASELINE_REVISION).put("baselineSourceSha256", BASELINE_SHA256)
            .put("baselineTransform", "Only CarbonDialArt/CarbonDialGeometry identifier renames; androidTest-only fixture")
            .put("baselineTimeModelSha256", "479db69ede0c8196b93bd73a290d93b77a79694d58a7e30549400707d0e2b0df")
            .put("baselinePusherGeometrySha256", "1079d8adcd097aef726dc6cedc2c06134f2af92a76070ccccd29027b041a6f44")
            .put("sdk", Build.VERSION.SDK_INT).put("hardware", Build.HARDWARE).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("densityDpi", metrics.densityDpi).put("startedAtEpochMillis", started)
            .put("finishedAtEpochMillis", System.currentTimeMillis()).put("images", entries).put("details", details)
        File(directory, "metadata.json").writeText(metadata.toString(2))
    }

    companion object {
        const val BASELINE_REVISION = "7802ee5096529a2b4713891261c3f46c909b8ac6"
        const val BASELINE_SHA256 = "ec00b8eec29cad69f46e0ee6d8ea1a1719b0fd524b7cdc4c539a9d389b386d0f"
        val EPOCH: Long = Instant.parse("2026-10-15T10:10:30Z").toEpochMilli()
        fun open(section: String): CarbonCaptureArtifacts {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue("Visual capture is opt-in", arguments.getString("captureCarbonBridge") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue("Never capture or modify production Nano", context.packageName.endsWith(".clockqa"))
            assertFalse("Public-only evidence must not contain the personal font", context.assets.list("")!!.any { it.equals("Porscha911.ttf", true) })
            val revision = arguments.getString("captureRevision").orEmpty()
            require(revision.matches(Regex("[0-9a-f]{40}"))) { "captureRevision must be the exact built source commit" }
            val run = arguments.getString("captureRun").orEmpty()
            require(run.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Supply a unique safe captureRun" }
            val directory = File(requireNotNull(context.getExternalFilesDir("carbon-bridge-captures")), "$run/$section")
            check(directory.mkdirs()) { "Capture directory already exists or is unavailable; do not overwrite evidence" }
            return CarbonCaptureArtifacts(directory, section)
        }
        private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
