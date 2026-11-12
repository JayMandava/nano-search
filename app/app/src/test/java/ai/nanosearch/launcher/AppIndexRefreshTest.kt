package ai.nanosearch.launcher

import org.junit.Assert.*
import org.junit.Test

class AppIndexRefreshTest {
    @Test fun unhideRebuildsEvenWhenLifecycleFlagIsFalse() {
        assertTrue(AppIndexRefresh.needsRebuild(false, 20, "", "calculator"))
    }
    @Test fun hideRebuildsWithoutAStartTransition() {
        assertTrue(AppIndexRefresh.needsRebuild(false, 21, "calculator", ""))
    }
    @Test fun firstSnapshotAndEmptyIndexAlwaysRebuild() {
        assertTrue(AppIndexRefresh.needsRebuild(false, 21, "", null))
        assertTrue(AppIndexRefresh.needsRebuild(false, 0, "", ""))
    }
    @Test fun unchangedIndexedSnapshotDoesNotRebuildUnlessPackagesChanged() {
        assertFalse(AppIndexRefresh.needsRebuild(false, 21, "", ""))
        assertTrue(AppIndexRefresh.needsRebuild(true, 21, "", ""))
    }
}
