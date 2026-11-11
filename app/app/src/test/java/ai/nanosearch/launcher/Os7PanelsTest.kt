package ai.nanosearch.launcher

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test

class Os7PanelsTest {
    private val alpha = Os7Panels.App("a/.Main", "Alpha")
    private val beta = Os7Panels.App("b/.Main", "Beta", ApplicationInfo.CATEGORY_AUDIO)
    private val system = Os7Panels.App("s/.Main", "System", isSystem = true)
    private val apps = listOf(system, beta, alpha)

    @Test fun allIsStableAndDeduplicated() {
        assertEquals(listOf(alpha, beta, system), Os7Panels.select(Os7Panels.Panel.ALL, apps + alpha))
    }

    @Test fun favoriteMembershipDoesNotInventMissingApps() {
        assertEquals(listOf(beta), Os7Panels.select(Os7Panels.Panel.FAVORITES, apps, listOf("missing/.Main", beta.component)))
    }

    @Test fun downloadedAppsExcludeSystemPackages() {
        assertEquals(listOf(alpha, beta), Os7Panels.select(Os7Panels.Panel.DOWNLOADS, apps))
    }

    @Test fun mediaUsesMetadataAndExactPackageFallbackNotDisplayName() {
        assertEquals(listOf(beta), Os7Panels.select(Os7Panels.Panel.MEDIA, apps))
        assertTrue(Os7Panels.isMedia(Os7Panels.App("com.spotify.music/.Main", "Anything")))
        assertFalse(Os7Panels.isMedia(Os7Panels.App("com.spotify.music.fake/.Main", "Music Photos")))
        assertTrue(Os7Panels.isMedia(alpha.copy(category = ApplicationInfo.CATEGORY_IMAGE)))
        assertTrue(Os7Panels.isMedia(alpha.copy(category = ApplicationInfo.CATEGORY_VIDEO)))
    }

    @Test fun frequentUsesPositiveCountsAndAlphabeticalTieBreakNotAppOrder() {
        val counts = mapOf(alpha.component to 2L, beta.component to 4L, system.component to 0L)
        assertEquals(listOf(beta, alpha), Os7Panels.select(Os7Panels.Panel.FREQUENT, apps, counts = counts, order = listOf(alpha.component)))
        assertEquals(listOf(alpha, beta), Os7Panels.select(Os7Panels.Panel.FREQUENT, apps, counts = counts + (beta.component to 2L)))
        assertTrue(Os7Panels.select(Os7Panels.Panel.FREQUENT, apps).isEmpty())
    }

    @Test fun customOrderingPreservesUnavailableAppsAndNewAppsHaveStableFallback() {
        assertEquals(listOf(system, alpha, beta), Os7Panels.select(Os7Panels.Panel.ALL, apps, order = listOf("missing", system.component)))
        assertEquals(listOf("b", "a", "missing"), Os7Panels.mergeOrder(listOf("b", "b", ""), listOf("a", "missing", "b")))
    }

    @Test fun movingFolderMembershipDoesNotDuplicateOrDropOtherMembers() {
        val first = Os7Panels.Folder("1", "One", listOf("a", "b"))
        val second = Os7Panels.Folder("2", "Two", listOf("c"))
        val moved = Os7Panels.moveToFolder(listOf(first, second), "2", "a")
        assertEquals(listOf(first.copy(components = listOf("b")), second.copy(components = listOf("c", "a"))), moved)
        assertEquals(moved, Os7Panels.moveToFolder(moved, "2", "a"))
        assertEquals(moved, Os7Panels.moveToFolder(moved, "missing", "a"))
    }

    @Test fun allPanelCannotBeHiddenAndUnknownPanelNamesAreIgnored() {
        assertEquals(listOf(Os7Panels.Panel.ALL), Os7Panels.visiblePanels(Os7Panels.Panel.entries.map { it.name }.toSet()))
        assertEquals(Os7Panels.Panel.entries, Os7Panels.visiblePanels(setOf("UNKNOWN")))
    }

    @Test fun folderRenamePreservesMembershipAndRejectsBlankNames() {
        val original = listOf(Os7Panels.Folder("1", "One", listOf("a")))
        assertEquals(original, Os7Panels.renameFolder(original, "1", "   "))
        assertEquals(original, Os7Panels.renameFolder(original, "missing", "Other"))
        assertEquals(listOf(original.first().copy(name = "New")), Os7Panels.renameFolder(original, "1", " New "))
        assertEquals(40, Os7Panels.renameFolder(original, "1", "x".repeat(60)).first().name.length)
    }

    @Test fun launchCountSaturatesAndRejectsOwnExactPackageOnly() {
        assertEquals(1L, AppUsage.incrementCount(0))
        assertEquals(8L, AppUsage.incrementCount(7))
        assertEquals(1L, AppUsage.incrementCount(-1))
        assertEquals(Long.MAX_VALUE, AppUsage.incrementCount(Long.MAX_VALUE))
        assertFalse(AppUsage.isExternalComponent("ai.nano/.Main", "ai.nano"))
        assertTrue(AppUsage.isExternalComponent("ai.nano.other/.Main", "ai.nano"))
        assertFalse(AppUsage.isExternalComponent("broken", "ai.nano"))
    }
}
