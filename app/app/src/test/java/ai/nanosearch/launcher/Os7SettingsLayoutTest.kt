package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.SettingsSurfaceTheme
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose geometry and controls; no models or network downloads are needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w360dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Os7SettingsLayoutTest {
    class HostRule : ExternalResource() {
        lateinit var controller: ActivityController<ComponentActivity>
        override fun before() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
        override fun after() { controller.pause().stop().destroy() }
    }

    private val host = HostRule()
    @get:Rule val compose = AndroidComposeTestRule(host) { host.controller.get() }
    private var fontScale by mutableFloatStateOf(1f)

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nano", 0).edit().clear().commit()
        AppSettings.setOs7Mode(app, true)
        ModelStore.init(app)
        ModelStore.status.clear()
    }

    @After fun cleanup() {
        ModelStore.status.clear()
        ModelStore.dir.deleteRecursively()
    }

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                SettingsSurfaceTheme { Box(Modifier.width(320.dp)) { body() } }
            }
        }
        compose.waitForIdle()
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Expected laid-out text: $text", layouts.isNotEmpty())
        assertTrue("Text must wrap without clipping: $text at font scale $fontScale; " +
            layouts.joinToString { "size=${it.size}, constraints=${it.layoutInput.constraints}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}" },
            layouts.none { it.hasVisualOverflow })
    }

    @Test fun modelSelectorsDescriptionsAndActionsStaySeparatedOnNarrowScreensAtLargeFonts() {
        val entry = ModelCatalog.all.single { it.id == "ocr-ppocrv4" }
        content { ModelCard(entry, false, 0, Long.MAX_VALUE, true) {} }

        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val card = compose.onNodeWithTag("model-${entry.id}").fetchSemanticsNode().boundsInRoot
            val selector = compose.onNodeWithTag("model-selector-${entry.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText(entry.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val description = compose.onNodeWithTag("model-description-${entry.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val actions = compose.onNodeWithTag("model-actions-${entry.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Selector and title need a visible gutter", title.left >= selector.right + 12f)
            assertTrue("Description belongs below the selector", description.top >= selector.bottom + 12f)
            assertTrue("Description belongs below the title", description.top >= title.bottom + 12f)
            assertEquals(card.left + 16f, description.left, 1f)
            assertEquals(card.right - 16f, description.right, 1f)
            assertTrue("Actions must not crowd the description", actions.top > description.bottom)
            assertTrue("Actions remain inside the card", actions.bottom <= card.bottom)
            assertTextFits(entry.name)
            assertTextFits(entry.note)
            assertTextFits("Download")
            // Material controls may draw smaller than their expanded accessible touch target.
            val download = compose.onNode(hasClickAction() and hasText("Download")).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("Download keeps its accessible target", download.height >= 48f)
        }
    }

    @Test fun sourceDescriptionGetsItsOwnLineAndRepeatedTogglesPersist() {
        val source = AppSettings.Source("layout-fixture", "Photo search", "Find pictures by their date, place, content and the text inside them.", null)
        content { SourceRow(source, 0) }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val title = compose.onNodeWithText(source.label).fetchSemanticsNode().boundsInRoot
            val toggle = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
            val description = compose.onNodeWithText(source.detail).fetchSemanticsNode().boundsInRoot
            assertTrue(toggle.left >= title.right + 16f)
            assertTrue(description.top >= maxOf(title.bottom, toggle.bottom) + 8f)
            assertEquals(title.left, description.left, 1f)
            assertTrue(description.width > title.width)
            val touchTarget = compose.onNode(isToggleable()).fetchSemanticsNode().touchBoundsInRoot
            assertTrue(touchTarget.width >= 48f && touchTarget.height >= 48f)
            assertTextFits(source.label)
            assertTextFits(source.detail)
            compose.onNode(isToggleable()).performClick().assertIsOff()
            assertFalse(AppSettings.sourceEnabled(RuntimeEnvironment.getApplication(), source.kind))
            compose.onNode(isToggleable()).performClick().assertIsOn()
            assertTrue(AppSettings.sourceEnabled(RuntimeEnvironment.getApplication(), source.kind))
        }
    }

    @Test fun permissionActionDoesNotSqueezeSourceDescription() {
        val source = AppSettings.SOURCES.single { it.kind == "photo" }
        content { SourceRow(source, 0) }
        compose.runOnIdle { fontScale = 2f }
        val allow = compose.onNode(hasClickAction() and hasText("Allow")).fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithText(source.detail).fetchSemanticsNode().boundsInRoot
        assertTrue(description.top >= allow.bottom + 8f)
        assertTextFits(source.detail)
        compose.onNodeWithText("Needs permission").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
