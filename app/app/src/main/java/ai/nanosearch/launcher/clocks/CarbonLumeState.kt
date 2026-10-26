package ai.nanosearch.launcher.clocks

/** A transient crown effect; it never changes chronograph or persisted widget state. */
data class CarbonLumeState(val startedAtMillis: Long? = null) {
    fun active(nowElapsed: Long): Boolean = startedAtMillis?.let { nowElapsed >= it && nowElapsed - it < DURATION_MILLIS } == true
    fun alpha(nowElapsed: Long, reducedMotion: Boolean): Float {
        if (!active(nowElapsed)) return 0f
        val elapsed = nowElapsed - requireNotNull(startedAtMillis)
        if (reducedMotion || elapsed <= HOLD_MILLIS) return 1f
        return (1f - (elapsed - HOLD_MILLIS).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
    }
    fun trigger(nowElapsed: Long): CarbonLumeState = CarbonLumeState(nowElapsed.coerceAtLeast(0L))
    fun clear(): CarbonLumeState = CarbonLumeState()
    companion object {
        const val HOLD_MILLIS = 1_200L
        const val FADE_MILLIS = 1_800L
        const val DURATION_MILLIS = HOLD_MILLIS + FADE_MILLIS
    }
}
