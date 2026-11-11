package ai.nanosearch.launcher

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Os7PersistenceTest {
    private lateinit var context: Context

    @Before fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        listOf("nano", "nano_app_launches", "nano_os7_panels").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun countsPersistIndependentlyOfRecentRemovalAndFavorites() {
        AppUsage.toggleFavourite(context, "a/.Main")
        AppUsage.note(context, "a/.Main")
        AppUsage.note(context, "b/.Main")
        AppUsage.note(context, "a/.Main")
        AppUsage.note(context, "${context.packageName}/.Main")
        assertEquals(mapOf("a/.Main" to 2L, "b/.Main" to 1L), AppUsage.counts(context))
        assertEquals(listOf("a/.Main", "b/.Main"), AppUsage.recents(context).map { it.first })
        AppUsage.forget(context, "a/.Main")
        assertEquals(2L, AppUsage.counts(context)["a/.Main"])
        assertEquals(listOf("a/.Main"), AppUsage.favourites(context))
    }

    @Test fun existingRecentsDoNotBecomeFrequencyCounts() {
        context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit()
            .putString("recents", "a/.Main@123").putString("favourites", "a/.Main").commit()
        assertTrue(AppUsage.counts(context).isEmpty())
        assertEquals(listOf("a/.Main" to 123L), AppUsage.recents(context))
        assertEquals(listOf("a/.Main"), AppUsage.favourites(context))
    }

    @Test fun foldersRoundTripMoveRenameRemoveAndDeleteWithoutTouchingApps() {
        assertNull(Os7Panels.createFolder(context, "  "))
        val first = Os7Panels.createFolder(context, " Music ", listOf("a/.Main", "b/.Main"))!!
        val second = Os7Panels.createFolder(context, "Other")!!
        AppUsage.toggleFavourite(context, "a/.Main")
        Os7Panels.saveOrder(context, listOf("a/.Main", "b/.Main"))
        Os7Panels.addToFolder(context, second.id, "a/.Main")
        Os7Panels.renameFolder(context, second.id, " New ")
        assertEquals(listOf("b/.Main"), Os7Panels.folders(context).first().components)
        assertEquals(Os7Panels.Folder(second.id, "New", listOf("a/.Main")), Os7Panels.folders(context).last())
        Os7Panels.removeFromFolder(context, first.id, "b/.Main")
        assertTrue(Os7Panels.folders(context).first().components.isEmpty())
        Os7Panels.deleteFolder(context, second.id)
        assertEquals(listOf(first.copy(components = emptyList())), Os7Panels.folders(context))
        assertEquals(listOf("a/.Main", "b/.Main"), Os7Panels.order(context))
        assertEquals(listOf("a/.Main"), AppUsage.favourites(context))
    }

    @Test fun panelVisibilityPersistsButAllRemainsAvailable() {
        Os7Panels.Panel.entries.forEach { Os7Panels.setPanelVisible(context, it, false) }
        assertEquals(listOf(Os7Panels.Panel.ALL), Os7Panels.visiblePanels(context))
        Os7Panels.setPanelVisible(context, Os7Panels.Panel.MEDIA, true)
        assertEquals(listOf(Os7Panels.Panel.ALL, Os7Panels.Panel.MEDIA), Os7Panels.visiblePanels(context))
    }

    @Test fun invalidFolderDataDoesNotCrashAndValidUnicodeNamesRoundTrip() {
        context.getSharedPreferences("nano_os7_panels", Context.MODE_PRIVATE).edit().putString("folders", "not JSON").commit()
        assertTrue(Os7Panels.folders(context).isEmpty())
        val folder = Os7Panels.createFolder(context, "Musique 🎵 \"live\"", listOf("a/.Main"))!!
        assertEquals(listOf(folder), Os7Panels.folders(context))
    }
}
