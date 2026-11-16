package com.demon.cmfassistant.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Places, distances and routes from OpenStreetMap services (no key needed). */
class Geo(private val context: Context) {
    class Pt(val name: String, val lat: Double, val lon: Double, val exact: Boolean = false)
    class Leg(val meters: Double, val seconds: Double)
    class Found(val name: String, val lat: Double, val lon: Double, val meters: Double)

    private fun get(url: String, timeout: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "CmfAssistant/1.0 (personal voice assistant)")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Where the phone is: the real location when Location is allowed, else the network's rough one. */
    fun here(): Pt? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .filter { System.currentTimeMillis() - it.time < 6 * 3600_000L }
                    .minByOrNull { it.accuracy }
                if (best != null) return Pt("your location", best.latitude, best.longitude, exact = true)
            }
        }
        runCatching {
            val j = JSONObject(get("https://ipwho.is/", 6000))
            if (j.optBoolean("success", true) && j.has("latitude")) return Pt("your area", j.getDouble("latitude"), j.getDouble("longitude"))
        }
        runCatching {
            val j = JSONObject(get("https://ipapi.co/json/", 6000))
            if (j.has("latitude")) return Pt("your area", j.getDouble("latitude"), j.getDouble("longitude"))
        }
        return null
    }

    fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "home"/"here"/"me" are the phone's own place; anything else is looked up, preferring results close to [near]. */
    fun place(q: String, near: Pt?): Pt? {
        val t = q.trim().removePrefix("the ").trim()
        if (t.lowercase() in setOf("here", "me", "my location", "my place", "current location", "where i am", "my current location", "this place")) return near ?: here()
        val box = near?.let { "&viewbox=${it.lon - 0.6},${it.lat + 0.6},${it.lon + 0.6},${it.lat - 0.6}" }.orEmpty()
        val arr = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3$box&q=" + URLEncoder.encode(t, "UTF-8")))
        val r = arr.optJSONObject(0) ?: return null
        val name = r.optString("name").ifBlank { r.optString("display_name").substringBefore(",") }.ifBlank { t }
        return Pt(name, r.getDouble("lat"), r.getDouble("lon"))
    }

    /** Distance and time by [mode]: driving, walking or cycling. */
    fun route(a: Pt, b: Pt, mode: String): Leg? {
        val base = when (mode) {
            "walking" -> "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
            "cycling" -> "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
            else -> "https://router.project-osrm.org/route/v1/driving/"
        }
        val j = JSONObject(get("$base${a.lon},${a.lat};${b.lon},${b.lat}?overview=false", 9000))
        val r = j.optJSONArray("routes")?.optJSONObject(0) ?: return null
        return Leg(r.getDouble("distance"), r.getDouble("duration"))
    }

    fun straight(a: Pt, b: Pt): Double = meters(a.lat, a.lon, b.lat, b.lon)

    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** The nearest places of a kind (hotel, park, school...) around [c], nearest first. */
    fun nearby(term: String, c: Pt, max: Int = 6): List<Found> {
        for (span in listOf(0.04, 0.12, 0.3)) {
            val box = "&viewbox=${c.lon - span},${c.lat + span},${c.lon + span},${c.lat - span}&bounded=1"
            val arr = runCatching { JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=20$box&q=" + URLEncoder.encode(term, "UTF-8"))) }.getOrNull() ?: continue
            val out = ArrayList<Found>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val name = r.optString("name").ifBlank { r.optString("display_name").substringBefore(",") }
                val lat = r.getDouble("lat")
                val lon = r.getDouble("lon")
                out.add(Found(name, lat, lon, meters(c.lat, c.lon, lat, lon)))
            }
            val sorted = out.distinctBy { it.name.lowercase() }.sortedBy { it.meters }
            if (sorted.isNotEmpty()) return sorted.take(max)
        }
        return emptyList()
    }

    companion object {
        fun distanceText(m: Double): String = if (m < 950) "${(m / 10).roundToInt() * 10} metres" else {
            val km = m / 1000
            if (km < 10) "%.1f kilometres".format(km) else "${km.roundToInt()} kilometres"
        }

        fun durationText(s: Double): String {
            val min = (s / 60).roundToInt()
            return when {
                min < 1 -> "under a minute"
                min < 60 -> "$min minutes"
                else -> {
                    val h = min / 60
                    val m = min % 60
                    "$h ${if (h == 1) "hour" else "hours"}" + if (m > 0) " $m minutes" else ""
                }
            }
        }
    }
}
