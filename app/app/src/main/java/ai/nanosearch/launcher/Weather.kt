package ai.nanosearch.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
    class Now(val tempC: Double, val sky: Sky, val day: Boolean, val at: Long, val hiC: Double = Double.NaN, val loC: Double = Double.NaN, val lat: Double = Double.NaN, val lon: Double = Double.NaN)

    private const val REFRESH_MS = 30 * 60 * 1000L
    /** A reading older than this is not shown at all (offline for hours): better no number than a wrong one. */
    private const val STALE_MS = 6 * 60 * 60 * 1000L
    /** Moving further than this from where a reading was taken makes it out of date at once. */
    private const val MOVED_KM = 10.0
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

    /** Optional: use the phone's approximate location instead of a chosen city. Needs the coarse location permission; off if it is not granted. */
    fun locationGranted(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun useLocation(c: Context) = prefs(c).getBoolean("weather_loc", false) && locationGranted(c)
    fun setUseLocation(c: Context, on: Boolean) = prefs(c).edit().putBoolean("weather_loc", on).remove("weather_now").apply()

    @Suppress("MissingPermission")
    private fun lastKnown(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Where to ask about: the approximate location (rounded to about a kilometre, named after the nearest city) if chosen and known, else the chosen city. */
    fun spot(c: Context): Triple<String, Double, Double>? {
        if (useLocation(c)) lastKnown(c)?.let { l ->
            val lat = Math.round(l.latitude * 100) / 100.0; val lon = Math.round(l.longitude * 100) / 100.0
            return Triple(Gazetteer.nearest(c, lat, lon)?.city ?: "Here", lat, lon)
        }
        return city(c)
    }

    /** Fahrenheit instead of Celsius. Defaults to what the phone's region uses. */
    fun fahrenheit(c: Context) = if (prefs(c).contains("weather_f")) prefs(c).getBoolean("weather_f", false) else java.util.Locale.getDefault().country in setOf("US", "BS", "BZ", "KY", "LR")
    fun setFahrenheit(c: Context, on: Boolean) = prefs(c).edit().putBoolean("weather_f", on).apply()

    /** The last reading we have, even if old; null when off, no city, or never fetched. */
    fun cached(c: Context): Now? {
        if (!enabled(c)) return null
        val j = runCatching { JSONObject(prefs(c).getString("weather_now", null) ?: return null) }.getOrNull() ?: return null
        val now = runCatching {
            Now(j.getDouble("t"), Sky.entries.getOrElse(j.getInt("s")) { Sky.CLEAR }, j.getBoolean("d"), j.getLong("at"), j.optDouble("hi", Double.NaN), j.optDouble("lo", Double.NaN), j.optDouble("la", Double.NaN), j.optDouble("lo2", Double.NaN))
        }.getOrNull() ?: return null
        if (System.currentTimeMillis() - now.at > STALE_MS) return null
        // a reading for somewhere else (you have travelled, or changed city) is not this place's weather
        spot(c)?.let { (_, lat, lon) -> if (!now.lat.isNaN() && !now.lon.isNaN() && km(lat, lon, now.lat, now.lon) > MOVED_KM) return null }
        return now
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun shown(c: Context, celsius: Double) = if (fahrenheit(c)) celsius * 9 / 5 + 32 else celsius

    fun format(c: Context, w: Now): String = "${Math.round(shown(c, w.tempC))}°"

    /** Today's high and low, or null when the reading has none. */
    fun highLow(c: Context, w: Now): Pair<String, String>? =
        if (w.hiC.isNaN() || w.loC.isNaN()) null else "${Math.round(shown(c, w.hiC))}°" to "${Math.round(shown(c, w.loC))}°"

    /** The conditions in words, as a weather widget writes them. */
    fun label(w: Now) = when (w.sky) {
        Sky.CLEAR -> if (w.day) "Sunny" else "Clear"
        Sky.PARTLY -> if (w.day) "Partly sunny" else "Partly cloudy"
        Sky.CLOUDY -> "Cloudy"
        Sky.FOG -> "Foggy"
        Sky.RAIN -> "Rain"
        Sky.SNOW -> "Snow"
        Sky.STORM -> "Thunderstorm"
    }

    /** Fetches fresh conditions if the cached ones are old, then calls [done] on the calling thread's behalf (from the worker; post to the UI yourself). */
    fun refresh(c: Context, done: () -> Unit) {
        if (!enabled(c) || busy) return
        val (_, lat, lon) = spot(c) ?: return
        val last = cached(c) // null when there is none, it is stale, or it is for somewhere else
        if (last != null && !last.hiC.isNaN() && !last.lat.isNaN() && System.currentTimeMillis() - last.at < REFRESH_MS) return
        busy = true
        val app = c.applicationContext
        io.execute {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto")
                val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val body = try { if (conn.responseCode != 200) return@execute; conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val cur = JSONObject(body).getJSONObject("current")
                val sky = skyOf(cur.getInt("weather_code"))
                val daily = JSONObject(body).optJSONObject("daily")
                val hi = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
                val lo = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
                val now = JSONObject().put("hi", hi.takeUnless { it.isNaN() } ?: JSONObject.NULL).put("lo", lo.takeUnless { it.isNaN() } ?: JSONObject.NULL).put("la", lat).put("lo2", lon).put("t", cur.getDouble("temperature_2m")).put("s", sky.ordinal).put("d", cur.getInt("is_day") == 1).put("at", System.currentTimeMillis())
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
