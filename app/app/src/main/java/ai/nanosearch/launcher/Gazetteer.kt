package ai.nanosearch.launcher

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class Place(val city: String, val region: String, val country: String) {
    /** "Panaji, Goa, India": every word of it is searchable. */
    override fun toString() = listOf(city, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

/**
 * Offline reverse geocoding: GPS coordinates to a known city, with its region and country. The biggest city within
 * [NEAR_KM] wins (so central Paris is "Paris", not the arrondissement the point is closest to); failing that, the nearest one.
 * Data: GeoNames cities with 15,000+ people (https://www.geonames.org, CC BY 4.0), bundled as assets/places.tsv.
 * A photo taken away from any city gets the nearest city within [MAX_KM]; beyond that it gets no place.
 */
object Gazetteer {
    const val VERSION = 3 // bump when the data or the choice rule changes, so cached photo places are recomputed
    private const val MAX_KM = 250.0
    private const val NEAR_KM = 8.0
    private var loaded = false
    private lateinit var cities: Array<String>
    private lateinit var regions: Array<String>
    private lateinit var countries: Array<String>
    private lateinit var lats: FloatArray
    private lateinit var lons: FloatArray
    private lateinit var pops: IntArray

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val rows = context.assets.open("places.tsv").bufferedReader().readLines().map { it.split('\t') }.filter { it.size >= 5 }
        cities = Array(rows.size) { rows[it][0] }
        regions = Array(rows.size) { rows[it][1] }
        countries = Array(rows.size) { Locale("", rows[it][2]).getDisplayCountry(Locale.ENGLISH) }
        lats = FloatArray(rows.size) { rows[it][3].toFloat() }
        lons = FloatArray(rows.size) { rows[it][4].toFloat() }
        pops = IntArray(rows.size) { rows[it].getOrNull(5)?.toIntOrNull() ?: 0 }
        loaded = true
    }

    fun nearest(context: Context, lat: Double, lon: Double): Place? {
        load(context)
        val kmPerLon = 111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        var nearest = -1
        var nearestKm = MAX_KM
        var biggest = -1
        for (i in cities.indices) {
            val dLat = (lats[i] - lat) * 111.0
            if (abs(dLat) > MAX_KM) continue
            var dLon = lons[i] - lon
            if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
            val km = sqrt(dLat * dLat + (dLon * kmPerLon) * (dLon * kmPerLon))
            if (km < nearestKm) { nearestKm = km; nearest = i }
            if (km <= NEAR_KM && (biggest < 0 || pops[i] > pops[biggest])) biggest = i
        }
        val best = if (biggest >= 0) biggest else nearest
        return if (best >= 0) Place(cities[best], regions[best], countries[best]) else null
    }
}
