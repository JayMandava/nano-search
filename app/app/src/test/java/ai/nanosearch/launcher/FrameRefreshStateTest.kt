package ai.nanosearch.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRefreshStateTest {
    @Test fun completedRefreshDoesNotRunAgainWithoutANewerRequest() {
        val state = FrameRefreshState()
        assertTrue(state.beginRefresh())
        assertFalse(state.finishRefresh())
        assertTrue(state.beginRefresh())
    }

    @Test fun stateChangeDuringAReadQueuesOneFreshRead() {
        val state = FrameRefreshState()
        assertTrue(state.beginRefresh())
        repeat(3) { assertFalse(state.beginRefresh()) }
        assertTrue(state.finishRefresh())
        assertTrue(state.beginRefresh())
        assertFalse(state.finishRefresh())
    }

    @Test fun changeDuringTheFollowupReadIsNotLost() {
        val state = FrameRefreshState()
        assertTrue(state.beginRefresh())
        assertFalse(state.beginRefresh())
        assertTrue(state.finishRefresh())
        assertTrue(state.beginRefresh())
        assertFalse(state.beginRefresh())
        assertTrue(state.finishRefresh())
    }

    @Test fun stationaryFramesDrawImmediately() {
        val state = FrameRefreshState()
        assertTrue(state.requestDraw())
        assertFalse(state.setScrolling(false))
    }

    @Test fun updatesDuringASwipeDrawOnceWhenItEnds() {
        val state = FrameRefreshState()
        assertFalse(state.setScrolling(true))
        repeat(3) { assertFalse(state.requestDraw()) }
        assertFalse(state.setScrolling(true))
        assertTrue(state.setScrolling(false))
        assertFalse(state.setScrolling(false))
    }

    @Test fun swipeWithoutAnUpdateDoesNotCauseAnExtraDraw() {
        val state = FrameRefreshState()
        assertFalse(state.setScrolling(true))
        assertFalse(state.setScrolling(false))
    }
}
