package ai.nanosearch.launcher.clocks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarbonChronographStoreTest {
    private lateinit var context: IsolatedClockContext
    @Before fun fixture() { context = IsolatedClockContext(InstrumentationRegistry.getInstrumentation().targetContext, "chrono-store-${UUID.randomUUID()}") }
    @After fun clean() { context.clean() }

    @Test fun sameBootRestoresARunningIntervalAndHeldSplit() {
        val state = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 100).transition(CarbonPusherAction.SPLIT_RELEASE, 300)
        CarbonChronographStore(context, "builtin:carbon01:one").save(state, 500, 9)
        val restored = CarbonChronographStore(context, "builtin:carbon01:one").load(1000, 9)
        assertTrue(restored.running)
        assertEquals(900L, restored.elapsed(1000))
        assertEquals(200L, restored.displayElapsed(1000))
        assertEquals(200L, restored.splitMillis)
    }

    @Test fun rebootRestoresOnlyTheSavedPausedCheckpointAndClearsSplit() {
        val state = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 100).transition(CarbonPusherAction.SPLIT_RELEASE, 300)
        CarbonChronographStore(context, "builtin:carbon01:one").save(state, 500, 9)
        val restored = CarbonChronographStore(context, "builtin:carbon01:one").load(20, 10)
        assertFalse(restored.running)
        assertNull(restored.splitMillis)
        assertEquals(400L, restored.elapsed(20000))
    }

    @Test fun independentInstanceKeysNeverShareChronographState() {
        val one = CarbonChronographStore(context, "builtin:carbon01:one")
        val two = CarbonChronographStore(context, "builtin:carbon01:two")
        one.save(CarbonChronographState(accumulatedMillis = 800), 1000, 9)
        two.save(CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 1000), 1200, 9)
        assertEquals(800L, one.load(2000, 9).elapsed(2000))
        assertFalse(one.load(2000, 9).running)
        assertTrue(two.load(2000, 9).running)
        assertEquals(1000L, two.load(2000, 9).elapsed(2000))
        one.save(CarbonChronographState(), 2100, 9)
        assertEquals(0L, one.load(2200, 9).elapsed(2200))
        assertEquals(1200L, two.load(2200, 9).elapsed(2200))
    }

    @Test fun impossibleElapsedOriginFallsBackToTheCheckpoint() {
        val store = CarbonChronographStore(context, "builtin:carbon01:one")
        store.save(CarbonChronographState(startedAtMillis = 1000), 1500, 9)
        val restored = store.load(100, 9)
        assertFalse(restored.running)
        assertEquals(500L, restored.elapsed(1000))
    }
}
