package ai.nanosearch.launcher

import android.app.Application
import java.io.RandomAccessFile
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.junit.rules.ExternalResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ai.nanosearch.launcher.ui.NanoTheme

/** Actual Compose semantics/layout with a simulated Android lifecycle; not phone performance QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneFeedbackUiTest {
    class HostRule : ExternalResource() {
        lateinit var controller: ActivityController<ComponentActivity>
        override fun before() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
        override fun after() { controller.pause().stop().destroy() }
    }
    private val host = HostRule()
    @get:Rule val compose = AndroidComposeTestRule(host) { it.controller.get() }
    private val controller get() = host.controller
    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nano", 0).edit().clear().commit()
        ModelStore.init(RuntimeEnvironment.getApplication())
        ModelStore.status.clear()
    }
    @After fun cleanup() {
        ModelStore.status.clear()
        ModelStore.dir.deleteRecursively()
    }
    private fun content(body: @Composable () -> Unit) {
        compose.setContent { NanoTheme { body() } }
        compose.waitForIdle()
    }
    private fun advance(millis: Long) {
        // repeatOnLifecycle runs its delay on Android's main dispatcher. Drive that
        // scheduler, then let the Compose rule render the resulting state change.
        compose.waitForIdle()
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
    @Test fun noticeUsesAvailableWidthAndDismissTextSharesMessageStartAtLargeFontScale() {
        var fontScale by mutableFloatStateOf(1f)
        content {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 28.dp).testTag("available")) {
                    ClockNoticeCard("Changed.", {}, Modifier.testTag("notice"))
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val available = compose.onNodeWithTag("available").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("notice").fetchSemanticsNode().boundsInRoot
            val message = compose.onNodeWithText("Changed.", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val dismiss = compose.onNodeWithText("Dismiss", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(available.left, card.left, 1f)
            assertEquals(available.right, card.right, 1f)
            assertEquals(message.left, dismiss.left, 1f)
            assertTrue(dismiss.bottom <= card.bottom)
            assertTrue(compose.onNode(hasClickAction() and hasText("Dismiss")).fetchSemanticsNode().boundsInRoot.height >= 48f)
        }
    }
    @Test fun noticeExpiresAfterAccessibilityRecommendedTimeAndRepeatedMessageRestartsTimer() {
        val notices = ClockNotices().apply { show("Changed Home clock") }
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean): Long {
                assertEquals(6_000L, originalTimeoutMillis)
                assertTrue(containsText); assertTrue(containsControls)
                return 12_000L
            }
        }
        content {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                ClockNoticeExpiry(notices.text, notices.revision, notices::expire)
                notices.text?.let { ClockNoticeCard(it, notices::clear) }
            }
        }
        advance(7_000L)
        compose.onNodeWithText("Changed Home clock").assertExists()
        compose.runOnIdle { notices.show("Changed Home clock") }
        advance(7_000L)
        compose.onNodeWithText("Changed Home clock").assertExists()
        advance(6_000L)
        compose.runOnIdle { assertNull("Recommended timeout must clear model state", notices.text) }
        compose.onNodeWithText("Changed Home clock").assertDoesNotExist()
    }
    @Test fun manualDismissWorksAndLeavingScreenCancelsItsTimer() {
        val notices = ClockNotices().apply { show("Changed Home clock") }
        var visible by mutableStateOf(true)
        content {
            if (visible) {
                ClockNoticeExpiry(notices.text, notices.revision, notices::expire)
                notices.text?.let { ClockNoticeCard(it, notices::clear) }
            }
        }
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle { assertNull("Dismiss must clear model state", notices.text) }
        advance(100L)
        compose.onNodeWithText("Changed Home clock").assertDoesNotExist()
        compose.runOnIdle { notices.show("New notice"); visible = false }
        advance(10_000L)
        compose.runOnIdle { assertEquals("New notice", notices.text); visible = true }
        advance(7_000L)
        compose.onNodeWithText("New notice").assertDoesNotExist()
    }
    @Test fun onlyRequestedModelIsSelectedDuringDownloadAndCancelRestoresUsableFallback() {
        val gemma = ModelCatalog.all.single { it.id == "answer-gemma4-e2b" }
        val qwen = ModelCatalog.all.single { it.id == "answer-qwen35-2b" }
        RandomAccessFile(ModelStore.file(qwen.parts.single()), "rw").use { it.setLength(qwen.bytes) }
        ModelStore.select(gemma)
        ModelStore.status[gemma.file] = Transfer.Downloading(0, gemma.bytes)
        var version by mutableIntStateOf(0)
        content {
            androidx.compose.foundation.layout.Column {
                val choice = ModelStore.displayChoice(Slot.ANSWER).id
                for (entry in listOf(gemma, qwen)) key(entry.id) {
                    ModelCard(entry, entry.id == choice, version, Long.MAX_VALUE, true) { version++ }
                }
            }
        }
        compose.onNodeWithTag("model-${gemma.id}").assertIsSelected()
        compose.onNodeWithTag("model-${qwen.id}").assertIsNotSelected()
        compose.onNodeWithText("Selected when ready. Using ${qwen.name} until the download is verified.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("model-${gemma.id}").assertIsNotSelected()
        compose.onNodeWithTag("model-${qwen.id}").assertIsSelected()
        assertEquals(qwen.id, ModelStore.selected(Slot.ANSWER).id)
    }

    @Test fun installedModelHidesStaleErrorAndOtherModelsKeepTheirOwnFailure() {
        val gemma = ModelCatalog.all.single { it.id == "answer-gemma4-e2b" }
        val qwen = ModelCatalog.all.single { it.id == "answer-qwen35-2b" }
        RandomAccessFile(ModelStore.file(gemma.parts.single()), "rw").use { it.setLength(gemma.bytes) }
        ModelStore.status[gemma.file] = Transfer.Failed("Stale Gemma failure")
        ModelStore.status[qwen.file] = Transfer.Failed("Qwen connection failure")
        content {
            androidx.compose.foundation.layout.Column {
                for (entry in listOf(gemma, qwen)) key(entry.id) {
                    ModelCard(entry, entry.id == gemma.id, 0, Long.MAX_VALUE, true) {}
                }
            }
        }
        compose.onNodeWithText("Stale Gemma failure").assertDoesNotExist()
        compose.onNodeWithText("Qwen connection failure").assertExists()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithTag("model-${gemma.id}").assertIsSelected()
        compose.onNodeWithTag("model-${qwen.id}").assertIsNotSelected()
    }

    @Test fun realModelsScreenKeepsSelectionAttachedToIdsAcrossLazyScrolling() {
        val entries = ModelCatalog.forSlot(Slot.ANSWER).take(2)
        entries.forEach { entry ->
            RandomAccessFile(ModelStore.file(entry.parts.single()), "rw").use { it.setLength(entry.bytes) }
        }
        ModelStore.select(entries.first())
        content { ModelsScreen {} }
        for (chosen in listOf(entries[1], entries[0], entries[1])) {
            compose.onNode(hasScrollAction()).performScrollToKey(chosen.id)
            compose.onNodeWithTag("model-${chosen.id}").performClick().assertIsSelected()
            val other = entries.single { it.id != chosen.id }
            compose.onNode(hasScrollAction()).performScrollToKey(other.id)
            compose.onNodeWithTag("model-${other.id}").assertIsNotSelected()
            assertEquals(chosen.id, ModelStore.selected(Slot.ANSWER).id)
        }
    }

    @Test fun noticeTimeDoesNotElapseWhileTheScreenIsPaused() {
        val notices = ClockNotices().apply { show("Changed Home clock") }
        content {
            ClockNoticeExpiry(notices.text, notices.revision, notices::expire)
            notices.text?.let { ClockNoticeCard(it, notices::clear) }
        }
        advance(2_000L)
        compose.runOnUiThread { controller.pause() }
        advance(10_000L)
        assertEquals("Changed Home clock", notices.text)
        compose.runOnUiThread { controller.resume() }
        advance(5_000L)
        compose.onNodeWithText("Changed Home clock").assertExists()
        advance(2_000L)
        compose.onNodeWithText("Changed Home clock").assertDoesNotExist()
    }

    @Test fun accessibilityCanKeepTheNoticeUntilManualDismiss() {
        val notices = ClockNotices().apply { show("Changed Home clock") }
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean) = Long.MAX_VALUE
        }
        content {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                ClockNoticeExpiry(notices.text, notices.revision, notices::expire)
                notices.text?.let { ClockNoticeCard(it, notices::clear) }
            }
        }
        advance(60_000L)
        compose.onNodeWithText("Changed Home clock").assertExists()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Changed Home clock").assertDoesNotExist()
    }
}
