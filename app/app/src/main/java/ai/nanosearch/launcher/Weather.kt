package ai.nanosearch.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The weather for the flip clock. Off by default. When switched on, the launcher asks Open-Meteo (a free service that needs no account or key) for the
 * current conditions at the one city the user chose, at most every 30 minutes while the clock is on screen. Only that city's coordinates leave the phone.
 */
object Weather {
    enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }
    class Now(val tempC: Double, val sky: Sky, val day: Boolean, val at: Long)

    private const val REFRESH_MS = 30 * 60 * 1000L
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("weather", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("weather", on).apply()

    /** The chosen city as its name, latitude and longitude, or null. */
    fun city(c: Context): Triple<String, Double, Double>? {
        val v = prefs(c).getString("weather_city", null)?.split('|') ?: return null
        return if (v.size == 3) Triple(v[0], v[1].toDoubleOrNull() ?: return null, v[2].toDoubleOrNull() ?: return null) else null
    }
    fun setCity(c: Context, name: String, lat: Double, lon: Double) = prefs(c).edit().putString("weather_city", "$name|$lat|$lon").remove("weather_now").apply()

    /** Fahrenheit instead of Celsius. Defaults to what the phone's region uses. */
    fun fahrenheit(c: Context) = if (prefs(c).contains("weather_f")) prefs(c).getBoolean("weather_f", false) else java.util.Locale.getDefault().country in setOf("US", "BS", "BZ", "KY", "LR")
    fun setFahrenheit(c: Context, on: Boolean) = prefs(c).edit().putBoolean("weather_f", on).apply()

    /** The last reading we have, even if old; null when off, no city, or never fetched. */
    fun cached(c: Context): Now? {
        if (!enabled(c)) return null
        val j = runCatching { JSONObject(prefs(c).getString("weather_now", null) ?: return null) }.getOrNull() ?: return null
        return Now(j.getDouble("t"), Sky.entries.getOrElse(j.getInt("s")) { Sky.CLEAR }, j.getBoolean("d"), j.getLong("at"))
    }

    fun format(c: Context, w: Now): String {
        val v = if (fahrenheit(c)) w.tempC * 9 / 5 + 32 else w.tempC
        return "${Math.round(v)}°"
    }

    /** Fetches fresh conditions if the cached ones are old, then calls [done] on the calling thread's behalf (from the worker; post to the UI yourself). */
    fun refresh(c: Context, done: () -> Unit) {
        if (!enabled(c) || busy) return
        val (_, lat, lon) = city(c) ?: return
        val last = cached(c)
        if (last != null && System.currentTimeMillis() - last.at < REFRESH_MS) return
        busy = true
        val app = c.applicationContext
        io.execute {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto")
                val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val cur = JSONObject(body).getJSONObject("current")
                val sky = skyOf(cur.getInt("weather_code"))
                val now = JSONObject().put("t", cur.getDouble("temperature_2m")).put("s", sky.ordinal).put("d", cur.getInt("is_day") == 1).put("at", System.currentTimeMillis())
                prefs(app).edit().putString("weather_now", now.toString()).apply()
                done()
            } catch (_: Exception) {
            } finally { busy = false }
        }
    }

    /** WMO weather codes, as Open-Meteo reports them, boiled down to what the clock can draw. */
    fun skyOf(code: Int) = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }
}
