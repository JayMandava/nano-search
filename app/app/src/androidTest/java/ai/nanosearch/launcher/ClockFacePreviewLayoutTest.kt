package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.ui.NanoTheme
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockFacePreviewLayoutTest {
    private data class WindowCase(val width: Int, val height: Int, val fontScale: Float)
    private val cases = listOf(
        WindowCase(360, 740, 1f), // Portrait.
        WindowCase(900, 280, 1f), // Landscape: width is deliberately much larger than the old 360dp cap.
        WindowCase(900, 180, 2f), // Compact-height landscape and large accessibility text.
        WindowCase(280, 480, 2f), // Narrow portrait with multiline labels.
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    // Do not use boundsInRoot(): that can report already-clipped bounds and hide an oversized child.
    private fun LayoutCoordinates.rawBounds(): Rect {
        val position = positionInRoot()
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    @Test fun staticCarbonArtworkFitsItsReportedSlotAndNeverOverlapsFollowingText() {
        val bitmap = ClockFaces.preview(instrumentation.targetContext, ClockFaces.CARBON_ID, 720, 1_792_058_430_000L)
        try { verifyCases(bitmap) } finally { bitmap.recycle() }
    }

    @Test fun liveCarbonCanvasUsesTheSameSquareSlotAcrossWindowSizesAndLargeText() = verifyCases(null)

    private fun verifyCases(bitmap: Bitmap?) {
        ActivityScenario.launch(ClockFacesActivity::class.java).use { scenario ->
            for (window in cases) {
                val frame = AtomicReference<Rect?>()
                val art = AtomicReference<Rect?>()
                val description = AtomicReference<Rect?>()
                val metadata = AtomicReference<Rect?>()
                val native = AtomicReference<DetailClockPreview?>()
                scenario.onActivity { activity ->
                    activity.setContent {
                        // Fixed pixel/dp density gives repeatable compact/expanded constraints on any
                        // phone, without changing system orientation, density or accessibility settings.
                        key(window) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, window.fontScale)) {
                            NanoTheme {
                                Layout(modifier = Modifier.fillMaxSize(), content = {
                                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ClockFacePreviewFrame(clockDetailPreviewMaxSide(window.height.dp), Modifier.onGloballyPositioned { frame.set(it.rawBounds()) }) {
                                            val bounds = Modifier.matchParentSize().onGloballyPositioned { art.set(it.rawBounds()) }
                                            if (bitmap != null) Image(bitmap.asImageBitmap(), "Carbon 01 preview at 10:10", bounds)
                                            else AndroidView(
                                                factory = { DetailClockPreview(it, ClockFaces.CARBON_ID, "Carbon 01").also(native::set) },
                                                modifier = bounds,
                                                onReset = null,
                                                onRelease = { it.visibility = android.view.View.GONE },
                                            )
                                        }
                                        Text("Static preview at 10:10, using the same renderer as Home and Lock Clock.", Modifier.fillMaxWidth().onGloballyPositioned { description.set(it.rawBounds()) })
                                        Text("Built-in · Included with Nano", Modifier.fillMaxWidth().onGloballyPositioned { metadata.set(it.rawBounds()) })
                                    }
                                }) { children, constraints ->
                                    // A real bounded scroll viewport, including the unbounded vertical
                                    // child measurement that originally exposed the aspectRatio bug.
                                    val child = children.single().measure(Constraints.fixed(window.width, window.height))
                                    layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, 0) }
                                }
                            }
                        }
                        }
                    }
                }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while ((frame.get() == null || art.get() == null || description.get() == null || metadata.get() == null ||
                        (bitmap == null && (native.get() == null || native.get()?.width == 0))) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                scenario.onActivity {
                    val label = "$window ${if (bitmap == null) "live" else "static"}"
                    val f = requireNotNull(frame.get()) { "Frame not measured: $label" }
                    val a = requireNotNull(art.get()) { "Artwork not measured: $label" }
                    val d = requireNotNull(description.get()) { "Description not measured: $label" }
                    val m = requireNotNull(metadata.get()) { "Metadata not measured: $label" }
                    val expectedSide = minOf(window.width.toFloat(), 360f, window.height * .65f)
                    assertEquals("Square artwork: $label", a.width, a.height, 1f)
                    assertEquals("Actual child and reported frame height agree: $label", f.height, a.height, 1f)
                    assertTrue("Artwork respects both viewport height and width: $label", a.width <= expectedSide + 1f)
                    assertTrue("Artwork left/right stay inside frame: $label", a.left >= f.left - 1f && a.right <= f.right + 1f)
                    assertTrue("Artwork top/bottom stay inside frame: $label", a.top >= f.top - 1f && a.bottom <= f.bottom + 1f)
                    assertTrue("Description starts after actual artwork, not merely a coerced parent: $label", d.top >= a.bottom)
                    assertTrue("Large text keeps its own measured space: $label", d.height > 0f && m.top >= d.bottom)
                    if (bitmap == null) {
                        val view = requireNotNull(native.get())
                        assertEquals("Native canvas width matches its Compose slot", a.width.toInt(), view.width)
                        assertEquals("Native canvas height matches its Compose slot", a.height.toInt(), view.height)
                    }
                }
            }
        }
    }
}
