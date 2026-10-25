package ai.nanosearch.launcher.clocks

import java.util.TimeZone

/** Pure local-time angles; no system services or per-frame Calendar allocations. */
object CarbonTimeModel {
    data class Angles(val hour: Float, val minute: Float, val second: Float)

    fun angles(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Angles {
        val localMillis = Math.floorMod(epochMillis + zone.getOffset(epochMillis), 86_400_000L)
        val second = (localMillis % 60_000L) / 1000f
        val minute = (localMillis / 60_000L % 60L) + second / 60f
        val hour = (localMillis / 3_600_000L % 12L) + minute / 60f
        return Angles(hour * 30f, minute * 6f, second * 6f)
    }
}
