package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.ClockTestHostActivity
import android.widget.FrameLayout
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.lifecycle.Lifecycle
import java.util.concurrent.TimeUnit
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockFacesActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun clockChoices(): Map<String, *> = instrumentation.targetContext
        .getSharedPreferences("nano", Context.MODE_PRIVATE).all.filterKeys { it.startsWith("clock_face_") || it.startsWith("lockclock_") || it == "widgets" || it.startsWith("widgets_") }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { done = check() }
            if (!done) SystemClock.sleep(50)
        }
        assertTrue(message, done)
    }

    @Test fun detailSelectionSurvivesRecreationWithoutApplyingAnything() {
        val before = clockChoices()
        ActivityScenario.launch(ClockFacesActivity::class.java).use { scenario ->
            lateinit var original: ClockFacesModel
            scenario.onActivity {
                original = ViewModelProvider(it)[ClockFacesModel::class.java]
                original.selectedId = ClockFaces.CARBON_ID
            }
            await("Collection must finish its asynchronous load") { original.snapshot.ready }
            scenario.recreate()
            scenario.onActivity {
                val recreated = ViewModelProvider(it)[ClockFacesModel::class.java]
                assertSame("Rotation must retain the single operation owner", original, recreated)
                assertEquals(ClockFaces.CARBON_ID, recreated.selectedId)
                assertNull(recreated.operation)
                assertEquals("Browsing is not an apply action", before, clockChoices())
            }
        }
    }

    private data class QaNode(val info: AccessibilityNodeInfo, val parent: QaNode?, val depth: Int)
    private var lastQaUi = "No QA accessibility snapshot yet"
    private val dialogTitle = "Operation in progress"
    private val knownLabels = setOf(dialogTitle, "Stay", "Cancel operation", "Install clock face")

    /** Compose 1.9.1 does not implement provider.findAccessibilityNodeInfosByText. Walk children. */
    @Suppress("DEPRECATION")
    private fun <T> withQaTrees(requiredLabels: Set<String>?, read: (List<List<QaNode>>) -> T): T {
        val started = SystemClock.uptimeMillis()
        val automation = instrumentation.uiAutomation
        val roots = buildList<AccessibilityNodeInfo> {
            val active = automation.rootInActiveWindow
            active?.let(::add)
            automation.windows.forEach { window ->
                // The active root is also in windows. Walking both repeats every remote node
                // request and can spend the blocked provider's lifetime inspecting the same UI.
                try { if (window.id != active?.windowId) window.root?.let(::add) } finally { window.recycle() }
            }
        }
        val owned = roots.toMutableList()
        val trees = mutableListOf<List<QaNode>>()
        try {
            for (root in roots) {
                // Never inspect or log another application's node text (this also excludes IME roots).
                if (root.packageName?.toString() != instrumentation.targetContext.packageName) continue
                val nodes = mutableListOf<QaNode>()
                val foundLabels = mutableSetOf<String>()
                val queue = java.util.ArrayDeque<QaNode>()
                queue.add(QaNode(root, null, 0))
                while (queue.isNotEmpty() && nodes.size < 1024) {
                    val node = queue.removeFirst()
                    nodes += node
                    foundLabels += knownLabels.filter { matches(node, it) }
                    // Positive queries only need these actual labels and their already-walked
                    // ancestors. Absence checks still inspect the complete bounded tree.
                    if (requiredLabels != null && foundLabels.containsAll(requiredLabels)) break
                    if (node.depth < 32) repeat(node.info.childCount.coerceAtMost(128).coerceAtMost((1024 - nodes.size - queue.size).coerceAtLeast(0))) { index ->
                        node.info.getChild(index)?.let { child ->
                            owned += child
                            queue.add(QaNode(child, node, node.depth + 1))
                        }
                    }
                }
                trees += nodes
                // A complete positive match in the active dialog needs no background-window walk.
                if (requiredLabels != null && foundLabels.containsAll(requiredLabels)) break
            }
            val labels = knownLabels.filter { label -> trees.any { nodes -> nodes.any { matches(it, label) } } }
            lastQaUi = "QA roots=${trees.size}, nodes=${trees.sumOf { it.size }}, expected labels=$labels, scanMs=${SystemClock.uptimeMillis() - started}"
            Log.i("ClockFacesTest", "ui uptime=${SystemClock.uptimeMillis()} $lastQaUi")
            return read(trees)
        } finally { owned.forEach { it.recycle() } }
    }

    private fun matches(node: QaNode, label: String) = node.info.isVisibleToUser &&
        (node.info.text?.toString()?.trim() == label || node.info.contentDescription?.toString()?.trim() == label)

    private fun awaitQaUi(message: String, requiredLabels: Set<String>? = null, condition: (List<List<QaNode>>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (withQaTrees(requiredLabels, condition)) return
            SystemClock.sleep(100)
        }
        fail("$message; $lastQaUi")
    }

    private fun clickDialogButton(label: String) {
        awaitQaUi("Could not activate the actual dialog button '$label'", setOf(dialogTitle, "Stay", "Cancel operation")) { trees ->
            trees.any { nodes ->
                // Match a dialog, not the operation card's identically named Cancel button.
                val completeDialog = listOf(dialogTitle, "Stay", "Cancel operation").all { expected -> nodes.any { matches(it, expected) } }
                completeDialog && nodes.filter { matches(it, label) }.any { found ->
                    var candidate: QaNode? = found
                    var clicked = false
                    while (candidate != null && !clicked) {
                        val info = candidate.info
                        if (info.isVisibleToUser && info.isEnabled && info.isClickable &&
                            info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                            clicked = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.i("ClockFacesTest", "dialogClick=$label uptime=${SystemClock.uptimeMillis()} accepted=$clicked")
                        }
                        candidate = candidate.parent
                    }
                    clicked
                }
            }
        }
    }

    private fun pressBackOnReadyScreen(scenario: ActivityScenario<ClockFacesActivity>) {
        lateinit var activity: ClockFacesActivity
        scenario.onActivity { activity = it }
        // RESUMED alone does not imply the first Compose frame/BackHandler effect has run.
        await("The recreated QA screen must attach its Back callback and regain window focus") {
            !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                activity.hasWindowFocus() && activity.onBackPressedDispatcher.hasEnabledCallbacks()
        }
        awaitQaUi("The rendered operation controls must be visible before pressing Back", setOf("Install clock face", "Cancel operation")) { trees ->
            trees.any { nodes -> nodes.any { matches(it, "Install clock face") } && nodes.any { matches(it, "Cancel operation") } }
        }
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertTrue(it.onBackPressedDispatcher.hasEnabledCallbacks())
            val model = ViewModelProvider(it)[ClockFacesModel::class.java]
            assertNotNull("Back must still be testing the blocked operation, not a completed/expired import", model.operation)
            logPhase("back-before", model, it)
            it.onBackPressedDispatcher.onBackPressed()
            logPhase("back-dispatched", model, it)
        }
    }

    /** Only test-owned state/categories: no document URI, foreign node text or user content. */
    private fun logPhase(phase: String, model: ClockFacesModel, activity: ClockFacesActivity) {
        val operation = model.operation
        val notice = when {
            model.notice == null -> "none"
            model.notice?.contains("cancelled", ignoreCase = true) == true -> "cancelled"
            model.notice?.contains("too long", ignoreCase = true) == true -> "timeout"
            else -> "other-result"
        }
        Log.i("ClockFacesTest", "phase=$phase uptime=${SystemClock.uptimeMillis()} model=${System.identityHashCode(model)} operation=${operation?.let { System.identityHashCode(it) }} stage=${operation?.progress?.stage} cancelling=${operation?.cancelling} notice=$notice selected=${model.selectedId} lifecycle=${activity.lifecycle.currentState} focus=${activity.hasWindowFocus()} back=${activity.onBackPressedDispatcher.hasEnabledCallbacks()} providerRequests=${ClockFaceBlockingProvider.requests.get()} cancellationPending=${ClockFaceBlockingProvider.cancellationObserved.count}")
    }

    @Test fun rotationAndBackCancelKeepOneProviderOperationAndPreserveInstalledState() {
        ClockFaceBlockingProvider.reset()
        val automation = instrumentation.uiAutomation
        val originalServiceFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            ActivityScenario.launch(ClockFacesActivity::class.java).use { scenario ->
                lateinit var original: ClockFacesModel
                scenario.onActivity { original = ViewModelProvider(it)[ClockFacesModel::class.java] }
                await("Collection must finish loading") { original.snapshot.ready }
                val before = original.snapshot.packs.map { it.id to it.digest }
                val beforeChoices = clockChoices()
                scenario.onActivity {
                    assertNull("Blocking-provider fixture requires a healthy collection", original.snapshot.error)
                    original.importDocument(ClockFaceBlockingProvider.uri(it))
                    original.importDocument(ClockFaceBlockingProvider.uri(it)) // A repeated tap must not queue another operation.
                    logPhase("import-started", original, it)
                }
                val reachedProvider = ClockFaceBlockingProvider.opened.await(15, TimeUnit.SECONDS)
                assertTrue("Provider open must reach the deterministic blocking point; UI result: ${original.notice}", reachedProvider)
                assertEquals("Read-mode ContentResolver must reach the typed provider entry point", 1, ClockFaceBlockingProvider.typedRequests.get())
                assertEquals("The typed provider must preserve the CancellationSignal", 1, ClockFaceBlockingProvider.cancellableRequests.get())
                Log.i("ClockFacesTest", "phase=provider-open-observed uptime=${SystemClock.uptimeMillis()}")
                scenario.recreate()
                scenario.onActivity {
                    assertSame(original, ViewModelProvider(it)[ClockFacesModel::class.java])
                    assertNotNull(original.operation)
                    assertEquals("Rotation/repeated clicks must not reopen a provider", 1, ClockFaceBlockingProvider.requests.get())
                    logPhase("recreated", original, it)
                }
                pressBackOnReadyScreen(scenario)
                clickDialogButton("Stay")
                awaitQaUi("Stay must dismiss the confirmation dialog") { trees ->
                    trees.isNotEmpty() && trees.none { nodes -> nodes.any { matches(it, dialogTitle) } }
                }
                scenario.onActivity {
                    assertNotNull("Stay must preserve the original operation", original.operation)
                    assertEquals("Stay must not cancel the provider", 1L, ClockFaceBlockingProvider.cancellationObserved.count)
                    logPhase("stay-dismissed", original, it)
                }
                pressBackOnReadyScreen(scenario)
                clickDialogButton("Cancel operation")
                assertTrue("Cancel must reach the provider opening CancellationSignal", ClockFaceBlockingProvider.cancellationObserved.await(15, TimeUnit.SECONDS))
                Log.i("ClockFacesTest", "phase=provider-cancellation-observed uptime=${SystemClock.uptimeMillis()}")
                await("Cancellation must settle before the UI reports its result") { original.operation == null }
                scenario.onActivity {
                    assertEquals(1, ClockFaceBlockingProvider.requests.get())
                    assertEquals(before, original.snapshot.packs.map { it.id to it.digest })
                    assertEquals(beforeChoices, clockChoices())
                    assertTrue(original.notice?.contains("cancelled", ignoreCase = true) == true)
                    assertNull(original.updateReview)
                    logPhase("cancel-settled", original, it)
                }
            }
        } finally {
            ClockFaceBlockingProvider.release()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
        }
    }

    @Test fun activityTeardownCancelsBlockedProviderOpenWithoutKeepingDocumentAccess() {
        ClockFaceBlockingProvider.reset()
        val resolver = instrumentation.targetContext.contentResolver
        val grantsBefore = resolver.persistedUriPermissions.map { it.uri to it.isReadPermission }
        val scenario = ActivityScenario.launch(ClockFacesActivity::class.java)
        try {
            lateinit var model: ClockFacesModel
            scenario.onActivity { model = ViewModelProvider(it)[ClockFacesModel::class.java] }
            await("Collection must finish loading") { model.snapshot.ready }
            scenario.onActivity {
                assertNull("Blocking-provider fixture requires a healthy collection", model.snapshot.error)
                model.importDocument(ClockFaceBlockingProvider.uri(it))
            }
            val reachedProvider = ClockFaceBlockingProvider.opened.await(15, TimeUnit.SECONDS)
            assertTrue("Provider open must reach the deterministic blocking point; UI result: ${model.notice}", reachedProvider)
            assertEquals(1, ClockFaceBlockingProvider.typedRequests.get())
            assertEquals(1, ClockFaceBlockingProvider.cancellableRequests.get())
            scenario.close() // External Activity teardown, distinct from Back's in-screen cancel confirmation.
            assertTrue("ViewModel clearing must cancel an outstanding provider open", ClockFaceBlockingProvider.cancellationObserved.await(15, TimeUnit.SECONDS))
            assertEquals(grantsBefore, resolver.persistedUriPermissions.map { it.uri to it.isReadPermission })
        } finally { scenario.close(); ClockFaceBlockingProvider.release() }
    }

    @Test fun liveDetailIsNotAClockAppButtonAndDoesNotAnnounceEverySecond() {
        ActivityScenario.launch(ClockTestHostActivity::class.java).use { scenario ->
            lateinit var view: DetailClockPreview
            scenario.onActivity {
                view = DetailClockPreview(it, ClockFaces.CARBON_ID, "Carbon 01")
                it.setContentView(view)
                assertFalse(view.isClickable)
                assertFalse(view.performClick())
            }
            await("Visible detail must draw its stable accessible preview label") { view.contentDescription == "Carbon 01 live preview" }
            SystemClock.sleep(1200)
            scenario.onActivity {
                assertEquals("Carbon 01 live preview", view.contentDescription)
                it.setContentView(FrameLayout(it))
            }
            await("Leaving the detail must stop every frame callback") { !view.isAttachedToWindow && !view.isFrameScheduled }
        }
    }

    @Test fun unavailableDocumentEndsSafelyWithoutLeakingUriOrChangingCollection() {
        val beforeChoices = clockChoices()
        ActivityScenario.launch(ClockFacesActivity::class.java).use { scenario ->
            lateinit var model: ClockFacesModel
            scenario.onActivity { model = ViewModelProvider(it)[ClockFacesModel::class.java] }
            await("Collection must finish loading") { model.snapshot.ready }
            val before = model.snapshot.packs.map { it.id to it.digest }
            scenario.onActivity { model.importDocument(Uri.parse("content://nano.invalid.test.provider/private-path?token=never-display-this")) }
            await("A missing document provider must terminate the operation") { model.operation == null && model.notice != null }
            scenario.onActivity {
                assertFalse(model.notice!!.contains("private-path"))
                assertFalse(model.notice!!.contains("never-display-this"))
                assertEquals(before, model.snapshot.packs.map { it.id to it.digest })
                assertEquals(beforeChoices, clockChoices())
                assertNull(model.updateReview)
            }
        }
    }
}
