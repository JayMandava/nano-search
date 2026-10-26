package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import ai.nanosearch.launcher.clocks.packs.FacePackException
import ai.nanosearch.launcher.clocks.packs.FacePackOperationException
import ai.nanosearch.launcher.clocks.packs.FacePackFailure
import java.io.IOException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackClockApplyIntegrationTest {
    private lateinit var fixture: PackIntegrationFixture
    @Before fun setUp() { fixture = PackIntegrationFixture() }
    @After fun tearDown() { fixture.clean() }

    @Test fun importDoesNotApplyAndReplacingOneHomeClockPreservesItsStableIdentityAndGeometry() {
        val (first, second) = fixture.seedHome()
        val before = fixture.preferences.all.toMap()
        val pack = fixture.install()
        assertEquals("Installing is not an implicit selection", before, fixture.preferences.all.toMap())
        val token = PackClockApply.applyHome(fixture.context, pack.id, first, "0")
        assertEquals(first, token)
        assertEquals(pack.id, PackClockApply.faceForToken(fixture.context, first))
        assertEquals(ClockFaces.STEEL_ID, PackClockApply.faceForToken(fixture.context, second))
        assertEquals(ClockFaces.CARBON_ID, LockClock.face(fixture.context))
        assertEquals(before, fixture.preferences.all.filterKeys { it != PackClockApply.bindingKey(first) })
        assertEquals(ClockPackUsage(1, false), PackClockApply.removalUsage(fixture.context, pack.id))
    }

    @Test fun addingAClockUsesExactlyTheChosenPageAndNeverReplacesAnotherInstance() {
        val (first, second) = fixture.seedHome()
        val before = fixture.preferences.all.toMap()
        val pack = fixture.install()
        val added = PackClockApply.applyHome(fixture.context, pack.id, null, "work")
        assertTrue(PackClockApply.isClockToken(added))
        assertNotEquals(first, added); assertNotEquals(second, added)
        assertEquals("$second,$added", fixture.preferences.getString("widgets_work", null))
        assertEquals(before["widgets"], fixture.preferences.getString("widgets", null))
        assertEquals("0,300", fixture.preferences.getString("wsize_$added", null))
        assertEquals(pack.id, fixture.preferences.getString(PackClockApply.bindingKey(added), null))
        val allowed = setOf("widgets_work", "wsize_$added", PackClockApply.bindingKey(added))
        assertEquals(before.filterKeys { it !in allowed }, fixture.preferences.all.filterKeys { it !in allowed })
        assertEquals(listOf(first, second, added), PackClockApply.homePlacements(fixture.context).map { it.token })
    }

    @Test fun lockSelectionLeavesHomeAndPhotoBackgroundUntouched() {
        fixture.seedHome()
        val pack = fixture.install()
        val photo = LockClock.photoFile(fixture.context)
        val photoBytes = byteArrayOf(7, 3, 9, 1, 8)
        photo.writeBytes(photoBytes)
        val before = fixture.preferences.all.toMap()
        PackClockApply.applyLock(fixture.context, pack.id)
        assertEquals(pack.id, LockClock.face(fixture.context))
        assertEquals(before.filterKeys { it != "lockclock_face" }, fixture.preferences.all.filterKeys { it != "lockclock_face" })
        assertArrayEquals(photoBytes, photo.readBytes())
        assertEquals(ClockPackUsage(0, true), PackClockApply.removalUsage(fixture.context, pack.id))
    }

    @Test fun stalePageAndWrongPageInstanceAreRejectedWithoutPreferenceMutation() {
        val (first, _) = fixture.seedHome()
        val pack = fixture.install()
        val before = fixture.preferences.all.toMap()
        rejected { PackClockApply.applyHome(fixture.context, pack.id, first, "work") }
        rejected { PackClockApply.applyHome(fixture.context, pack.id, null, "removed-page") }
        rejected { PackClockApply.applyHome(fixture.context, pack.id, "42", "0") }
        assertEquals(before, fixture.preferences.all.toMap())
    }

    @Test fun removalResetsOnlyAffectedSelectionsAndReimportNeverResurrectsRemovedBindings() {
        val (first, second) = fixture.seedHome()
        val pack = fixture.install()
        PackClockApply.applyHome(fixture.context, pack.id, first, "0")
        PackClockApply.applyLock(fixture.context, pack.id)
        val before = fixture.preferences.all.toMap()
        assertEquals(ClockPackUsage(1, true), PackClockApply.removalUsage(fixture.context, pack.id))
        fixture.store.remove(pack.id) {
            assertNull("Removal must be visible before preference fallback", fixture.store.find(pack.id))
            PackClockApply.resetRemovedBindings(fixture.context, pack.id)
        }
        assertEquals(ClockFaces.STEEL_ID, PackClockApply.faceForToken(fixture.context, first))
        assertEquals(ClockFaces.STEEL_ID, PackClockApply.faceForToken(fixture.context, second))
        assertEquals(ClockFaces.STEEL_ID, LockClock.face(fixture.context))
        val changed = setOf(PackClockApply.bindingKey(first), "lockclock_face")
        assertEquals(before.filterKeys { it !in changed }, fixture.preferences.all.filterKeys { it !in changed })
        val preferencesAfterRemoval = fixture.preferences.all.toMap()
        val reimported = fixture.install()
        assertNotEquals("A removed internal ID is a tombstone, never a reinstall target", pack.id, reimported.id)
        assertEquals(preferencesAfterRemoval, fixture.preferences.all.toMap())
        rejected { PackClockApply.applyHome(fixture.context, pack.id, first, "0") }
        assertEquals(preferencesAfterRemoval, fixture.preferences.all.toMap())
    }

    @Test fun removingAHomePackNeverChangesAnIndependentCarbonLockChoice() {
        val (first, _) = fixture.seedHome()
        val pack = fixture.install()
        PackClockApply.applyHome(fixture.context, pack.id, first, "0")
        fixture.store.remove(pack.id) { PackClockApply.resetRemovedBindings(fixture.context, pack.id) }
        assertEquals(ClockFaces.CARBON_ID, LockClock.face(fixture.context))
        assertEquals(ClockFaces.STEEL_ID, PackClockApply.faceForToken(fixture.context, first))
    }

    @Test fun carbonChronographStateSurvivesAPackRoundTripUsingTheOriginalWidgetToken() {
        val (first, _) = fixture.seedHome()
        val state = CarbonChronographState(accumulatedMillis = 1_234L)
        val chronograph = CarbonChronographStore(fixture.context, first)
        chronograph.save(state, 2_000L, 9)
        val statePreferences = fixture.context.getSharedPreferences(CarbonChronographStore.PREFS_FILE, android.content.Context.MODE_PRIVATE)
        val before = statePreferences.all.toMap()
        val pack = fixture.install()
        assertEquals(first, PackClockApply.applyHome(fixture.context, pack.id, first, "0"))
        assertEquals(first, PackClockApply.applyHome(fixture.context, ClockFaces.CARBON_ID, first, "0"))
        assertEquals(before, statePreferences.all.toMap())
        assertEquals(state, chronograph.load(3_000L, 9))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recreated = ClockWidgets.forFace(PackClockApply.faceForToken(fixture.context, first)).create(fixture.context, first)
            assertTrue("Returning to Carbon must use the stateful Home factory", recreated is CarbonHomeClockView)
            recreated as CarbonHomeClockView
            assertEquals(first, recreated.instanceKey)
            assertEquals(1_234L, recreated.elapsedChronographMillis())
        }
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("A stale selection must be rejected") } catch (e: IOException) {
            assertTrue("Unexpected rejection: $e", e is FacePackException || (e is FacePackOperationException && e.reason == FacePackFailure.CONFLICT))
        }
    }
}
