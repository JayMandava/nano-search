package ai.nanosearch.launcher

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiStateTest {
    @Test fun enabledRadioIsOnWithoutRequiringAConnection() {
        assertEquals("Wi-Fi on", WifiState.label { WifiManager.WIFI_STATE_ENABLED })
    }

    @Test fun disabledRadioIsOff() {
        assertEquals("Wi-Fi off", WifiState.label { WifiManager.WIFI_STATE_DISABLED })
    }

    @Test fun transitionsAreNotMisreportedAsOff() {
        assertEquals("Wi-Fi turning on", WifiState.label { WifiManager.WIFI_STATE_ENABLING })
        assertEquals("Wi-Fi turning off", WifiState.label { WifiManager.WIFI_STATE_DISABLING })
    }

    @Test fun unknownAndUnexpectedStatesAreUnavailable() {
        assertEquals("Wi-Fi status unavailable", WifiState.label { WifiManager.WIFI_STATE_UNKNOWN })
        assertEquals("Wi-Fi status unavailable", WifiState.label { -1 })
    }

    @Test fun missingServiceIsUnavailable() {
        assertEquals("Wi-Fi status unavailable", WifiState.label { null })
    }

    @Test fun deniedPermissionIsNotOff() {
        assertEquals("Wi-Fi status unavailable", WifiState.label { throw SecurityException("denied") })
    }

    @Test fun serviceFailureIsNotOff() {
        assertEquals("Wi-Fi status unavailable", WifiState.label { throw IllegalStateException("unavailable") })
    }
}
