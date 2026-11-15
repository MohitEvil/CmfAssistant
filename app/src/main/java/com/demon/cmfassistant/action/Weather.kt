package com.demon.cmfassistant.action

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Weather from Open-Meteo (no key). Place by name, or the rough location of the network when none is said. */
object Weather {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "CmfAssistant/1.0")
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private class Place(val name: String, val lat: Double, val lon: Double)

    private fun here(): Place? {
        runCatching {
            val j = JSONObject(get("https://ipwho.is/"))
            if (j.optBoolean("success", true) && j.has("latitude")) return Place(j.optString("city", "your area"), j.getDouble("latitude"), j.getDouble("longitude"))
        }
        runCatching {
            val j = JSONObject(get("https://ipapi.co/json/"))
            if (j.has("latitude")) return Place(j.optString("city", "your area"), j.getDouble("latitude"), j.getDouble("longitude"))
        }
        return null
    }

    private fun named(q: String): Place? {
        val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(q, "UTF-8")))
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(r.optString("name", q), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun describe(code: Int) = when (code) {
        0 -> "clear and sunny"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "foggy"; 51, 53, 55 -> "drizzling"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "raining"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snowing"; 80, 81 -> "rain showers"; 82 -> "heavy showers"
        85, 86 -> "snow showers"; 95 -> "thunderstorms"; 96, 99 -> "thunderstorms with hail"
        else -> "mixed"
    }

    suspend fun now(place: String?, tomorrow: Boolean): ActionResult = withContext(Dispatchers.IO) {
        try {
            val p = (if (place.isNullOrBlank()) here() else named(place))
                ?: return@withContext ActionResult.Failure(if (place.isNullOrBlank()) "I couldn't work out where you are. Say the city, like weather in Delhi." else "I couldn't find a place called $place.")
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2"
            val j = JSONObject(get(url))
            val d = j.getJSONObject("daily")
            fun day(i: Int) = Triple(
                d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt() to d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                d.getJSONArray("weather_code").getInt(i)
            )
            if (tomorrow) {
                val (hl, rain, code) = day(1)
                return@withContext ActionResult.Success("Tomorrow in ${p.name}: ${describe(code)}, high ${hl.first}°, low ${hl.second}°${if (rain >= 20) ", $rain% chance of rain" else ""}.")
            }
            val cur = j.getJSONObject("current")
            val t = cur.getDouble("temperature_2m").roundToInt()
            val feels = cur.getDouble("apparent_temperature").roundToInt()
            val hum = cur.getInt("relative_humidity_2m")
            val wind = cur.getDouble("wind_speed_10m").roundToInt()
            val (hl, rain, _) = day(0)
            ActionResult.Success(
                "Right now in ${p.name} it's $t°, ${describe(cur.getInt("weather_code"))}. Feels like $feels°, humidity $hum%, wind $wind kilometres an hour. " +
                    "Today's high is ${hl.first}° and low ${hl.second}°${if (rain >= 20) ", with a $rain% chance of rain" else ""}."
            )
        } catch (e: Exception) {
            ActionResult.Failure("I couldn't reach the weather service. Check your internet and try again.")
        }
    }
}
