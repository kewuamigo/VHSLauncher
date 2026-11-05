package hu.retro.vhslauncher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Current conditions for the VCR display. */
class Weather(val temperature: Int, val fahrenheit: Boolean, val kind: Kind, val city: String?) {

    enum class Kind { SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

    val text get() = "$temperature°${if (fahrenheit) "F" else "C"}"

    companion object {
        /** WMO weather interpretation codes, as used by Open-Meteo. */
        fun kindOf(code: Int, day: Boolean) = when (code) {
            0 -> if (day) Kind.SUN else Kind.MOON
            1, 2 -> Kind.PARTLY
            3 -> Kind.CLOUD
            45, 48 -> Kind.FOG
            in 51..67, in 80..82 -> Kind.RAIN
            in 71..77, 85, 86 -> Kind.SNOW
            in 95..99 -> Kind.STORM
            else -> Kind.CLOUD
        }
    }
}

/**
 * Blocking network calls; run them off the main thread.
 * Weather from Open-Meteo (no key); location from the saved city, else from the IP address.
 */
object WeatherService {

    class Place(val name: String, val lat: Double, val lon: Double)

    fun fetch(prefs: Prefs): Weather? {
        val place = prefs.cityName?.let { Place(it, prefs.cityLat.toDouble(), prefs.cityLon.toDouble()) } ?: locateByIp() ?: return null
        val unit = if (prefs.fahrenheit) "fahrenheit" else "celsius"
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit&timezone=auto"
        ) ?: return null
        val current = json.optJSONObject("current") ?: return null
        return Weather(
            temperature = current.optDouble("temperature_2m").roundToInt(),
            fahrenheit = prefs.fahrenheit,
            kind = Weather.kindOf(current.optInt("weather_code"), current.optInt("is_day", 1) == 1),
            city = place.name,
        )
    }

    /** Looks a city name up with Open-Meteo's geocoder. */
    fun geocode(name: String): Place? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val r = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1")?.optJSONArray("results") ?: return null
        val first = r.optJSONObject(0) ?: return null
        return Place(first.optString("name", name), first.optDouble("latitude"), first.optDouble("longitude"))
    }

    private fun locateByIp(): Place? {
        get("https://ipwho.is/")?.let { j ->
            if (j.optBoolean("success", false)) return Place(j.optString("city"), j.optDouble("latitude"), j.optDouble("longitude"))
        }
        get("https://ipapi.co/json/")?.let { j ->
            if (j.has("latitude")) return Place(j.optString("city"), j.optDouble("latitude"), j.optDouble("longitude"))
        }
        return null
    }

    private fun get(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "VHSLauncher/0.2 (Android TV)")
        try {
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
