package hu.retro.vhslauncher

import android.content.Context

/** Launcher settings, stored in SharedPreferences (safe to read from the GL thread). */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("vhs_launcher", Context.MODE_PRIVATE)

    /** App keys in the user's shelf order; apps not listed follow in A-Z order. */
    var order: List<String>
        get() = sp.getString("order", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString("order", v.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var sound by bool("sound", true)
    var volume by int("volume", 7)
    var weather by bool("weather", true)
    var fahrenheit by bool("fahrenheit", false)
    var clock24 by bool("clock24", true)
    var scanlines by bool("scanlines", true)
    var wobble by bool("wobble", true)

    /** Manually chosen weather location; null means detect it from the IP address. */
    var cityName: String?
        get() = sp.getString("city_name", null)
        set(v) = sp.edit().putString("city_name", v).apply()
    var cityLat by float("city_lat", 0f)
    var cityLon by float("city_lon", 0f)

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = sp.edit().putInt(key, value).apply()
    }

    private fun float(key: String, def: Float) = object : kotlin.properties.ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) = sp.edit().putFloat(key, value).apply()
    }
}
