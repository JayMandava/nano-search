package ai.nanosearch.launcher

/** Main-thread bookkeeping: coalesce refreshes without losing a change or redrawing during a swipe. */
internal class FrameRefreshState {
    private var loading = false
    private var refreshAgain = false
    private var scrolling = false
    private var pendingDraw = false

    fun beginRefresh(): Boolean {
        if (loading) { refreshAgain = true; return false }
        loading = true
        return true
    }

    /** Whether a newer request arrived while the worker was reading the previous state. */
    fun finishRefresh(): Boolean {
        loading = false
        val again = refreshAgain
        refreshAgain = false
        return again
    }

    fun requestDraw(): Boolean {
        pendingDraw = scrolling
        return !scrolling
    }

    /** Return true once when a deferred draw can safely run. */
    fun setScrolling(on: Boolean): Boolean {
        scrolling = on
        if (!on && pendingDraw) { pendingDraw = false; return true }
        return false
    }
}
