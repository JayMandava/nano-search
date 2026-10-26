package ai.nanosearch.launcher.clocks

import org.junit.Assert.*
import org.junit.Test

class CarbonChronographTest {
    @Test fun startPauseResumeUsesOnlyMonotonicElapsedTime() {
        var state = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 1000)
        assertTrue(state.running)
        assertEquals(1250L, state.elapsed(2250))
        state = state.transition(CarbonPusherAction.START_PAUSE, 2250)
        assertFalse(state.running)
        assertEquals(1250L, state.elapsed(500000))
        state = state.transition(CarbonPusherAction.START_PAUSE, 9000)
        assertEquals(1750L, state.elapsed(9500))
    }

    @Test fun rapidRepeatedTapsNeverLoseOrDoubleCountIntervals() {
        var state = CarbonChronographState()
        repeat(50) { index ->
            val start = 1000L + index * 1000L
            state = state.transition(CarbonPusherAction.START_PAUSE, start)
            state = state.transition(CarbonPusherAction.START_PAUSE, start + 100)
        }
        assertFalse(state.running)
        assertEquals(5000L, state.elapsed(100000))
        val sameTime = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 100).transition(CarbonPusherAction.START_PAUSE, 100)
        assertEquals(0L, sameTime.elapsed(100))
    }

    @Test fun splitFreezesOnlyTheReadingAndReleaseDoesNotStopTheChronograph() {
        var state = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 100)
        state = state.transition(CarbonPusherAction.SPLIT_RELEASE, 1100)
        assertEquals(1000L, state.displayElapsed(2100))
        assertEquals(2000L, state.elapsed(2100))
        assertTrue(state.running)
        state = state.transition(CarbonPusherAction.SPLIT_RELEASE, 3100)
        assertNull(state.splitMillis)
        assertEquals(3000L, state.displayElapsed(3100))
    }

    @Test fun splitIsRunningOnlyAndResetIsStoppedOnly() {
        val paused = CarbonChronographState(accumulatedMillis = 500)
        assertEquals(paused, paused.transition(CarbonPusherAction.SPLIT_RELEASE, 1000))
        assertEquals(CarbonChronographState(), paused.transition(CarbonPusherAction.RESET, 1000))
        val running = paused.transition(CarbonPusherAction.START_PAUSE, 1000)
        assertEquals(running, running.transition(CarbonPusherAction.RESET, 2000))
    }

    @Test fun pausingAHeldSplitClearsItAndPreservesTheActualTotal() {
        var state = CarbonChronographState().transition(CarbonPusherAction.START_PAUSE, 100).transition(CarbonPusherAction.SPLIT_RELEASE, 1100)
        state = state.transition(CarbonPusherAction.START_PAUSE, 4100)
        assertFalse(state.running)
        assertNull(state.splitMillis)
        assertEquals(4000L, state.displayElapsed(9000))
        assertEquals(CarbonChronographState(), state.transition(CarbonPusherAction.RESET, 10000))
    }

    @Test fun clockOpeningAndBackwardElapsedSamplesCannotMutateOrSubtractTime() {
        val state = CarbonChronographState(accumulatedMillis = 200, startedAtMillis = 1000)
        assertEquals(state, state.transition(CarbonPusherAction.OPEN_CLOCK, 9000))
        assertEquals(state, state.transition(CarbonPusherAction.LUME, 9000))
        assertEquals(200L, state.elapsed(500))
        assertEquals(90000200L, state.elapsed(90001000))
    }
}
