package com.azura.tvlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

object WeatherService {

    data class Weather(
        val tempC: Double,
        val code: Int,
        val place: String,
        /** Today's sunrise and sunset in the place's local time; null when Open-Meteo gave none. */
        val sunrise: LocalTime? = null,
        val sunset: LocalTime? = null,
    ) {
        fun temperature(fahrenheit: Boolean): String {
            val t = if (fahrenheit) tempC * 9 / 5 + 32 else tempC
            return "${t.roundToInt()}°"
        }
        val description: String get() = describe(code)
        val symbol: String get() = symbol(code)
    }

    private data class Place(val lat: Double, val lon: Double, val name: String)

    /**
     * Current conditions for [city] (a name typed in Settings) or, when blank, for [defaultCity].
     * Only Open-Meteo is contacted: its geocoder to turn the name into coordinates, then its
     * forecast endpoint. Nothing about the device (IP, location) is sent anywhere.
     */
    suspend fun fetch(city: String): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val place = geocode(city.ifBlank { defaultCity() })
            val json = JSONObject(
                Net.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                        "&current=temperature_2m,weather_code&daily=sunrise,sunset&forecast_days=1&timezone=auto"
                )
            )
            val current = json.getJSONObject("current")
            val daily = json.optJSONObject("daily")
            Weather(
                tempC = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                place = place.name,
                sunrise = daily?.optJSONArray("sunrise")?.optString(0)?.let(::localTime),
                sunset = daily?.optJSONArray("sunset")?.optString(0)?.let(::localTime),
            )
        }.getOrNull()
    }

    /**
     * A sensible place to show weather for before the user types one: the city in the box's
     * time zone ("America/Phoenix" -> "Phoenix", "Europe/London" -> "London"), else the
     * country of its locale.
     */
    fun defaultCity(): String {
        val zone = TimeZone.getDefault().id
        val leaf = zone.substringAfterLast('/').replace('_', ' ').trim()
        val usable = zone.contains('/') && !zone.startsWith("Etc/") &&
            leaf.isNotBlank() && !leaf.matches(Regex("(GMT|UTC|UCT|Universal|Zulu).*"))
        if (usable) return leaf
        return Locale.getDefault().displayCountry.ifBlank { "London" }
    }

    /** Open-Meteo's daily times come as "2026-11-01T06:52" in the place's own zone. */
    private fun localTime(iso: String): LocalTime? =
        runCatching { LocalDateTime.parse(iso).toLocalTime() }.getOrNull()

    private fun geocode(city: String): Place {
        val q = URLEncoder.encode(city, "UTF-8")
        val json = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val first = json.optJSONArray("results")?.optJSONObject(0)
            ?: throw IllegalStateException("No match for $city")
        return Place(first.getDouble("latitude"), first.getDouble("longitude"), first.optString("name", city))
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    private fun symbol(code: Int): String = when (code) {
        0, 1 -> "☀"
        2 -> "⛅"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "🌨"
        95, 96, 99 -> "⛈"
        else -> "•"
    }
}
