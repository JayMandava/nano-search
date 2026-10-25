package ai.nanosearch.launcher

import android.net.wifi.WifiManager

/** Radio state, not network connectivity: enabled but disconnected is still on. */
internal object WifiState {
    fun label(read: () -> Int?): String {
        val state = try { read() } catch (_: Exception) { null }
        return when (state) {
            WifiManager.WIFI_STATE_ENABLED -> "Wi-Fi on"
            WifiManager.WIFI_STATE_DISABLED -> "Wi-Fi off"
            WifiManager.WIFI_STATE_ENABLING -> "Wi-Fi turning on"
            WifiManager.WIFI_STATE_DISABLING -> "Wi-Fi turning off"
            else -> "Wi-Fi status unavailable"
        }
    }
}
