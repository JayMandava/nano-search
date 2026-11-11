package ai.nanosearch.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Behaviour-only tests: no native Services, installed apps, icon loading, or pixel assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [Os7HomeViewTest.NoIconResources::class])
class Os7HomeViewTest {
    // These tests exercise navigation before layout. Ignore header icon resources so the ordinary
    // JVM test configuration does not need packaged app resources or installed package icons.
    @Implements(ImageView::class)
    class NoIconResources : org.robolectric.shadows.ShadowView() {
        @Implementation
        protected fun setImageResource(resourceId: Int) = Unit
    }

    private lateinit var context: Context
    private val alpha = Item("app", "Alpha", "a/.Main")
    private val beta = Item("app", "Beta", "b/.Main")
    private val gamma = Item("app", "Gamma", "c/.Main")
    private val launched = mutableListOf<Item>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf("nano", "nano_app_launches", "nano_os7_panels").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        launched.clear()
    }

    private fun home(items: List<Item> = listOf(alpha, beta, gamma)) = Os7HomeView(
        context, launch = { item, _ -> launched.add(item) }, appMenu = { _, _ -> },
        search = {}, notifications = {}, connections = {}, profiles = {}, menu = {}
    ).also { view -> view.setApps(items, items.map { Os7Panels.App(it.key, it.title) }) }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun grid(home: Os7HomeView) = descendants(home).filterIsInstance<GridView>().single()
    private fun selected(home: Os7HomeView) = descendants(home).filterIsInstance<TextView>()
        .single { it.contentDescription?.contains("panel,") == true }
    private fun next(home: Os7HomeView) = descendants(home).filterIsInstance<TextView>()
        .single { it.text.endsWith(" ›") }
    private fun items(home: Os7HomeView): List<Item> = grid(home).adapter.let { adapter ->
        (0 until adapter.count).map { adapter.getItem(it) as Item }
    }
    private fun clickItem(home: Os7HomeView, position: Int) {
        assertTrue(grid(home).performItemClick(View(context), position, position.toLong()))
    }

    @Test fun trayStepsExpandBackAndResetWithoutChangingPreferences() {
        AppUsage.toggleFavourite(context, beta.key)
        Os7Panels.saveOrder(context, listOf(beta.key, alpha.key, gamma.key))
        val home = home()
        assertFalse(home.expanded)
        assertTrue(selected(home).contentDescription.contains("1 rows"))
        selected(home).performClick()
        assertTrue(home.expanded)
        assertTrue(selected(home).contentDescription.contains("2 rows"))
        selected(home).performClick()
        assertTrue(selected(home).contentDescription.contains("3 rows"))
        assertTrue(home.back())
        assertFalse(home.expanded)
        assertFalse(home.back())
        next(home).performClick()
        assertTrue(selected(home).text.startsWith("Favorites"))
        home.setExpanded(true)
        val notificationRow = descendants(home).single { it.contentDescription == "Sound profile" }.parent as View
        home.showSearch(true)
        assertEquals(View.INVISIBLE, notificationRow.visibility)
        home.reset()
        assertEquals(View.VISIBLE, notificationRow.visibility)
        assertTrue(selected(home).text.startsWith("All"))
        assertFalse(home.expanded)
        assertNull(home.folderId)
        assertEquals(listOf(beta, alpha, gamma), items(home))
        assertEquals(listOf(beta.key), AppUsage.favourites(context))
        assertEquals(listOf(beta.key, alpha.key, gamma.key), Os7Panels.order(context))
    }

    @Test fun folderClickOpensContentsAndBackReturnsWithoutLaunchingOrDeleting() {
        val folder = Os7Panels.createFolder(context, "Work", listOf(beta.key, gamma.key))!!
        val home = home()
        val position = items(home).indexOfFirst { it.kind == "folder" && it.key == folder.id }
        assertTrue(position >= 0)
        clickItem(home, position)
        assertEquals(folder.id, home.folderId)
        assertTrue(home.expanded)
        assertEquals(listOf(beta, gamma), items(home))
        assertTrue(launched.isEmpty())
        assertTrue(home.back())
        assertNull(home.folderId)
        assertTrue(items(home).any { it.kind == "folder" && it.key == folder.id })
        assertEquals(listOf(folder), Os7Panels.folders(context))
        assertTrue(home.back())
        assertFalse(home.expanded)
    }

    @Test fun panelCyclingSkipsHiddenPanelsAndWrapsToAll() {
        Os7Panels.setPanelVisible(context, Os7Panels.Panel.FAVORITES, false)
        Os7Panels.setPanelVisible(context, Os7Panels.Panel.DOWNLOADS, false)
        Os7Panels.setPanelVisible(context, Os7Panels.Panel.FREQUENT, false)
        val home = home()
        assertEquals("Media ›", next(home).text.toString())
        next(home).performClick()
        assertTrue(selected(home).text.startsWith("Media"))
        next(home).performClick()
        assertTrue(selected(home).text.startsWith("All"))
        Os7Panels.setPanelVisible(context, Os7Panels.Panel.MEDIA, false)
        home.refresh()
        assertEquals("All ›", next(home).text.toString())
        next(home).performClick()
        assertTrue(selected(home).text.startsWith("All"))
    }

    @Test fun frequentRanksByLaunchCountAndLaunchCallbackUsesDisplayedItem() {
        AppUsage.note(context, alpha.key)
        repeat(3) { AppUsage.note(context, beta.key) }
        Os7Panels.saveOrder(context, listOf(alpha.key, gamma.key, beta.key))
        val home = home()
        repeat(4) { next(home).performClick() }
        assertTrue(selected(home).text.startsWith("Frequent"))
        assertFalse(home.canReorder)
        assertEquals(listOf(beta, alpha), items(home))
        clickItem(home, 0)
        assertEquals(listOf(beta), launched)
        assertEquals(3L, AppUsage.counts(context)[beta.key]) // The launch owner, not this view, records success.
    }

    @Test fun refreshingFilteredAppsPreservesFolderButDoesNotResurrectMissingMembers() {
        val folder = Os7Panels.createFolder(context, "Work", listOf(alpha.key, beta.key))!!
        val home = home()
        clickItem(home, items(home).indexOfFirst { it.key == folder.id })
        home.setApps(listOf(beta), listOf(Os7Panels.App(beta.key, beta.title)))
        assertEquals(folder.id, home.folderId)
        assertEquals(listOf(beta), items(home))
        home.setApps(emptyList(), emptyList())
        assertEquals(folder.id, home.folderId)
        assertTrue(items(home).isEmpty())
        assertEquals(View.GONE, grid(home).visibility)
        assertEquals(listOf(folder), Os7Panels.folders(context))
    }
}
