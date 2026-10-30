package ai.nanosearch.launcher.clocks

import android.content.Context
import android.util.Base64

/** Controls belong to a Carbon home-widget instance; wallpaper has a separate rev-only action and Steel remains display-only. */
enum class CarbonPusherAction { START_PAUSE, SPLIT_RELEASE, RESET, OPEN_CLOCK, LUME, REV }

data class CarbonChronographState(
    val accumulatedMillis: Long = 0L,
    val startedAtMillis: Long? = null,
    val splitMillis: Long? = null,
) {
    val running: Boolean get() = startedAtMillis != null

    fun elapsed(nowElapsed: Long): Long {
        val accumulated = accumulatedMillis.coerceAtLeast(0L)
        val delta = startedAtMillis?.let { (nowElapsed - it).coerceAtLeast(0L) } ?: 0L
        return if (Long.MAX_VALUE - accumulated < delta) Long.MAX_VALUE else accumulated + delta
    }

    fun displayElapsed(nowElapsed: Long): Long = splitMillis ?: elapsed(nowElapsed)
    fun enabled(action: CarbonPusherAction): Boolean = when (action) {
        CarbonPusherAction.SPLIT_RELEASE -> running
        CarbonPusherAction.RESET -> !running
        else -> true
    }

    fun transition(action: CarbonPusherAction, nowElapsed: Long): CarbonChronographState {
        if (!enabled(action)) return this
        return when (action) {
            CarbonPusherAction.START_PAUSE -> if (running) {
                copy(accumulatedMillis = elapsed(nowElapsed), startedAtMillis = null, splitMillis = null)
            } else copy(startedAtMillis = nowElapsed.coerceAtLeast(0L), splitMillis = null)
            CarbonPusherAction.SPLIT_RELEASE -> copy(splitMillis = if (splitMillis == null) elapsed(nowElapsed) else null)
            CarbonPusherAction.RESET -> CarbonChronographState()
            CarbonPusherAction.OPEN_CLOCK, CarbonPusherAction.LUME, CarbonPusherAction.REV -> this
        }
    }
}

/** Separate storage never changes Nano widget tokens, layout or launcher preferences. */
class CarbonChronographStore(context: Context, val instanceKey: String) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val prefix = "v1." + Base64.encodeToString(instanceKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE) + "."

    init { require(instanceKey.isNotBlank()) }

    fun load(nowElapsed: Long, bootCount: Int): CarbonChronographState {
        fun long(name: String, fallback: Long): Long = try { prefs.getLong(prefix + name, fallback) } catch (_: ClassCastException) { fallback }
        fun integer(name: String, fallback: Int): Int = try { prefs.getInt(prefix + name, fallback) } catch (_: ClassCastException) { fallback }
        val accumulated = long("accumulated", 0L).coerceAtLeast(0L)
        val checkpoint = long("checkpoint", accumulated).coerceAtLeast(0L)
        val start = long("started", -1L)
        val sameBoot = bootCount >= 0 && integer("boot", -1) == bootCount
        val running = sameBoot && start >= 0L && start <= nowElapsed
        if (!running) return CarbonChronographState(if (start >= 0L) checkpoint else accumulated)
        val state = CarbonChronographState(accumulated, start)
        val split = long("split", -1L).takeIf { it >= 0L && it <= state.elapsed(nowElapsed) }
        return state.copy(splitMillis = split)
    }

    fun save(state: CarbonChronographState, nowElapsed: Long, bootCount: Int) {
        prefs.edit()
            .putLong(prefix + "accumulated", state.accumulatedMillis.coerceAtLeast(0L))
            .putLong(prefix + "started", state.startedAtMillis ?: -1L)
            .putLong(prefix + "split", state.splitMillis ?: -1L)
            .putLong(prefix + "checkpoint", state.elapsed(nowElapsed))
            .putInt(prefix + "boot", bootCount)
            .apply()
    }

    companion object { const val PREFS_FILE = "carbon_home_chronographs" }
}
