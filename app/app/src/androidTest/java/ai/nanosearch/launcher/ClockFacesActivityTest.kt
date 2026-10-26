package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.ClockTestHostActivity
import android.widget.FrameLayout
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.TimeUnit
import android.content.Context
import android.net.Uri
import android.os.SystemClock
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

    private fun clickVisibleText(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
            for (found in nodes) {
                var node: AccessibilityNodeInfo? = found
                repeat(5) {
                    val current = node
                    if (current != null && current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    node = current?.parent
                }
            }
            SystemClock.sleep(100)
        }
        fail("Couldn't activate visible control: $label")
    }

    @Test fun rotationAndBackCancelKeepOneProviderOperationAndPreserveInstalledState() {
        ClockFaceBlockingProvider.reset()
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
                }
                val reachedProvider = ClockFaceBlockingProvider.opened.await(15, TimeUnit.SECONDS)
                assertTrue("Provider open must reach the deterministic blocking point; UI result: ${original.notice}", reachedProvider)
                assertEquals("Read-mode ContentResolver must reach the typed provider entry point", 1, ClockFaceBlockingProvider.typedRequests.get())
                assertEquals("The typed provider must preserve the CancellationSignal", 1, ClockFaceBlockingProvider.cancellableRequests.get())
                scenario.recreate()
                scenario.onActivity {
                    assertSame(original, ViewModelProvider(it)[ClockFacesModel::class.java])
                    assertNotNull(original.operation)
                    assertEquals("Rotation/repeated clicks must not reopen a provider", 1, ClockFaceBlockingProvider.requests.get())
                    it.onBackPressedDispatcher.onBackPressed()
                }
                clickVisibleText("Stay")
                scenario.onActivity { assertNotNull(original.operation); it.onBackPressedDispatcher.onBackPressed() }
                clickVisibleText("Cancel operation")
                assertTrue("Cancel must reach the provider opening CancellationSignal", ClockFaceBlockingProvider.cancellationObserved.await(15, TimeUnit.SECONDS))
                await("Cancellation must settle before the UI reports its result") { original.operation == null }
                scenario.onActivity {
                    assertEquals(1, ClockFaceBlockingProvider.requests.get())
                    assertEquals(before, original.snapshot.packs.map { it.id to it.digest })
                    assertEquals(beforeChoices, clockChoices())
                    assertTrue(original.notice?.contains("cancelled", ignoreCase = true) == true)
                    assertNull(original.updateReview)
                }
            }
        } finally { ClockFaceBlockingProvider.release() }
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
