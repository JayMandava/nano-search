package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.CarbonCaptureArtifacts
import ai.nanosearch.launcher.clocks.CarbonHomeClockView
import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.ClockTestHostActivity
import ai.nanosearch.launcher.clocks.ClockWidgets
import ai.nanosearch.launcher.clocks.IsolatedClockContext
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual application screens and production widget components; no mock artwork or default-Home changes. */
@RunWith(AndroidJUnit4::class)
class CarbonBridgeUiCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun captureActualClockFacesPortraitLandscapeAndFactoryWidget() {
        val output = CarbonCaptureArtifacts.open("ui")
        val preferencesBefore = target.getSharedPreferences("nano", Context.MODE_PRIVATE).all.toMap()
        val oldZone = TimeZone.getDefault()
        val captures = JSONArray()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            ActivityScenario.launch(ClockFacesActivity::class.java).use { scenario ->
                for ((name, requested, expected) in listOf(
                    Triple("portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT),
                    Triple("landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE),
                )) {
                    scenario.onActivity { it.requestedOrientation = requested }
                    awaitActivity("Clock Faces must regain its own visible $name window", scenario) {
                        it.resources.configuration.orientation == expected && it.hasWindowFocus()
                    }
                    scenario.onActivity { ViewModelProvider(it)[ClockFacesModel::class.java].selectedId = null }
                    awaitQaLabel("Clock Faces", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    scrollQaToTop()
                    awaitQaLabel("Carbon 01 preview at 10:10", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    awaitQaLabel("Clock Faces", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    captures.put(capture(output, "clock-faces-collection-$name.png", scenario,
                        "Actual ClockFacesActivity collection; production static previews at 2026-10-15 10:10:30 UTC",
                        JSONObject().put("navigationGuard", "Clock Faces toolbar and Carbon card visible").put("scrollPolicy", "Scroll as needed to reveal Carbon card")))
                    scenario.onActivity { ViewModelProvider(it)[ClockFacesModel::class.java].selectedId = ClockFaces.CARBON_ID }
                    // This marker exists only in the detail; the old collection has the same image label.
                    awaitQaLabel("Live local-time preview", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    scrollQaToTop()
                    awaitQaLabel("Carbon 01 preview at 10:10", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    val preview = awaitFullDetailPreview(scenario)
                    captures.put(capture(output, "clock-faces-carbon-detail-$name.png", scenario,
                        "Actual ClockFacesActivity detail; production static preview at 2026-10-15 10:10:30 UTC",
                        JSONObject().put("navigationGuard", "Detail-only Live local-time preview marker; full square preview below toolbar")
                            .put("previewBoundsPx", JSONArray(listOf(preview.left, preview.top, preview.right, preview.bottom))) ))
                }
            }
            ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
                var fixtureContext: IsolatedClockContext? = null
                lateinit var widget: CarbonHomeClockView
                try {
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    awaitActivity("The debug widget host must be portrait and focused", scenario) {
                        it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && it.hasWindowFocus()
                    }
                    scenario.onActivity { activity ->
                        val context = IsolatedClockContext(activity, "carbon-visual-${UUID.randomUUID()}")
                        fixtureContext = context
                        widget = ClockWidgets.forFace(ClockFaces.CARBON_ID).create(context, "b:carbon01:visual-capture") as CarbonHomeClockView
                        val density = activity.resources.displayMetrics.density
                        val side = minOf((360 * density).toInt(), activity.resources.displayMetrics.widthPixels - (32 * density).toInt())
                        activity.setContentView(FrameLayout(activity).apply {
                            setBackgroundColor(Color.rgb(18, 22, 24))
                            addView(TextView(activity).apply {
                                text = "Nano Carbon 01 widget\nActual factory view in the QA debug host"
                                setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                            }, FrameLayout.LayoutParams(-1, (88 * density).toInt(), Gravity.TOP))
                            addView(widget, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
                        })
                    }
                    awaitActivity("The actual Nano widget must be attached and visible", scenario) {
                        widget.isRenderingVisible && widget.isFrameScheduled && widget.width > 0 && widget.height > 0
                    }
                    val widgetDetails = JSONObject()
                    scenario.onActivity {
                        val xy = IntArray(2); widget.getLocationOnScreen(xy)
                        widgetDetails.put("widgetClass", widget.javaClass.name)
                            .put("widgetBoundsPx", JSONArray(listOf(xy[0], xy[1], widget.width, widget.height)))
                            .put("clockTime", "Live wall time; not one of the fixed-time comparison images")
                            .put("container", "Existing debug-only activity; not the full Nano Home screen")
                    }
                    captures.put(capture(output, "nano-carbon-widget-portrait.png", scenario,
                        "Actual ClockWidgets factory CarbonHomeClockView; isolated preferences; live wall time", widgetDetails))
                } finally {
                    scenario.onActivity { it.setContentView(FrameLayout(it)) }
                    fixtureContext?.clean()
                }
            }
        } finally {
            TimeZone.setDefault(oldZone)
            output.finish(JSONObject().put("captures", captures)
                .put("wallpaperOrDefaultHomeChanged", false)
                .put("uiPreviewUtc", "2026-10-15T10:10:30Z")
                .put("widgetTimePolicy", "Unmodified real widget uses live wall time; direct native before/after PNGs use exact fixed time."))
            assertEquals("Visual browsing must preserve actual QA Home/Lock/preferences", preferencesBefore,
                target.getSharedPreferences("nano", Context.MODE_PRIVATE).all.toMap())
        }
    }

    private fun <A : Activity> capture(output: CarbonCaptureArtifacts, file: String, scenario: ActivityScenario<A>, origin: String,
                                     extra: JSONObject = JSONObject()): JSONObject {
        awaitActivity("Capture requires the intended QA activity to own focus", scenario) { it.hasWindowFocus() }
        val activeRoot = instrumentation.uiAutomation.rootInActiveWindow
        try { assertEquals("Never capture another application's window", target.packageName, activeRoot?.packageName?.toString()) }
        finally { @Suppress("DEPRECATION") activeRoot?.recycle() }
        scenario.onActivity {
            extra.put("activity", it.javaClass.name).put("orientation", it.resources.configuration.orientation)
                .put("densityDpi", it.resources.displayMetrics.densityDpi)
                .put("decorWidthPx", it.window.decorView.width).put("decorHeightPx", it.window.decorView.height)
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot capture failed" }
        return try { output.png(file, screenshot, origin, extra) } finally { screenshot.recycle() }
    }

    private fun <A : Activity> awaitActivity(message: String, scenario: ActivityScenario<A>, check: (A) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 90_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = check(it) }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue(message, ready)
    }

    private fun awaitQaLabel(label: String, scrollAction: Int) {
        var scrolls = 0
        var nextScrollAt = SystemClock.uptimeMillis() + 5_000
        val deadline = SystemClock.uptimeMillis() + 90_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (qaLabelVisible(label)) return
            if (scrolls < 4 && SystemClock.uptimeMillis() >= nextScrollAt) {
                scrollQa(scrollAction); scrolls++; nextScrollAt = SystemClock.uptimeMillis() + 5_000
            }
            SystemClock.sleep(100)
        }
        fail("Actual QA screen did not render its expected preview/control label: $label")
    }

    private fun scrollQaToTop() {
        repeat(8) {
            if (!scrollQa(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            SystemClock.sleep(250)
        }
    }

    private fun awaitFullDetailPreview(scenario: ActivityScenario<ClockFacesActivity>): Rect {
        val deadline = SystemClock.uptimeMillis() + 90_000
        var last = Rect()
        while (SystemClock.uptimeMillis() < deadline) {
            val preview = qaLabelBounds("Carbon 01 preview at 10:10")
            val title = qaLabelBounds("Carbon 01")
            val marker = qaLabelBounds("Live local-time preview")
            val viewport = Rect()
            var minimumSide = 100
            scenario.onActivity {
                val xy = IntArray(2); it.window.decorView.getLocationOnScreen(xy)
                viewport.set(xy[0], xy[1], xy[0] + it.window.decorView.width, xy[1] + it.window.decorView.height)
                minimumSide = (100 * it.resources.displayMetrics.density).toInt()
            }
            if (preview != null) {
                last = preview
                if (marker != null && title != null && preview.width() >= minimumSide &&
                    kotlin.math.abs(preview.width() - preview.height()) <= 1 && viewport.contains(preview) &&
                    preview.top >= title.bottom) return preview
            }
            SystemClock.sleep(100)
        }
        error("Detail preview is clipped, stale or not fully framed: $last")
    }

    @Suppress("DEPRECATION")
    private fun scrollQa(action: Int): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>(); nodes.add(root)
        var visited = 0
        try {
            if (root.packageName?.toString() != target.packageName) return false
            while (nodes.isNotEmpty() && visited++ < 1024) {
                val node = nodes.removeFirst()
                try {
                    if (node.isVisibleToUser && node.isScrollable && node.actionList.any { it.id == action } && node.performAction(action)) return true
                    repeat(node.childCount.coerceAtMost(128).coerceAtMost((1024 - visited - nodes.size).coerceAtLeast(0))) { i ->
                        node.getChild(i)?.let { nodes.add(it) }
                    }
                } finally { node.recycle() }
            }
            return false
        } finally { while (nodes.isNotEmpty()) nodes.removeFirst().recycle() }
    }

    @Suppress("DEPRECATION")
    private fun qaLabelVisible(label: String): Boolean = qaLabelBounds(label) != null

    @Suppress("DEPRECATION")
    private fun qaLabelBounds(label: String): Rect? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var visited = 0
        try {
            if (root.packageName?.toString() != target.packageName) return null
            while (nodes.isNotEmpty() && visited++ < 1024) {
                val node = nodes.removeFirst()
                try {
                    if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label))
                        return Rect().also(node::getBoundsInScreen)
                    repeat(node.childCount.coerceAtMost(128).coerceAtMost((1024 - visited - nodes.size).coerceAtLeast(0))) { i ->
                        node.getChild(i)?.let { nodes.add(it) }
                    }
                } finally { node.recycle() }
            }
            return null
        } finally { while (nodes.isNotEmpty()) nodes.removeFirst().recycle() }
    }
}
