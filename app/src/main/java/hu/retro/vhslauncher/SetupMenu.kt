package hu.retro.vhslauncher

/**
 * The SETUP tape's on-screen menu, in the style of a VCR's blue OSD. Pure state and key
 * handling; [Art.menu] draws it. Runs on the GL thread.
 */
class SetupMenu(private val prefs: Prefs, private val host: Host) {

    interface Host {
        fun askCity()
        fun openAccessibilitySettings()
        fun homeButtonEnabled(): Boolean
        fun weatherSettingsChanged()
        fun click()
    }

    /** [bar] >= 0 draws a 10-step level instead of [value]. */
    class Row(val label: String, val value: String = "", val bar: Int = -1, val grabbed: Boolean = false, val dim: Boolean = false)
    class View(val title: String, val rows: List<Row>, val cursor: Int, val hint: String)

    private enum class Page { MAIN, APPS }
    private enum class Item { APPS, SOUND, VOLUME, WEATHER, LOCATION, UNITS, CLOCK, SCANLINES, WOBBLE, HOME, EXIT }

    /** Bumped on every visible change, so the renderer knows when to repaint. */
    var version = 0
        private set

    /** City found by IP lookup, shown next to AUTO. */
    var detectedCity: String? = null
        set(v) { field = v; changed() }

    private var page = Page.MAIN
    private var cursor = 0
    private var first = 0
    private var grabbed = false
    private var defaults: List<LaunchItem> = emptyList()
    private val apps = mutableListOf<LaunchItem>()
    private val hidden = mutableSetOf<String>()

    fun open(all: List<LaunchItem>) {
        defaults = all
        apps.clear(); apps += AppRepository.ordered(all, prefs)
        hidden.clear(); hidden += prefs.hidden
        page = Page.MAIN; cursor = 0; first = 0; grabbed = false
        changed()
    }

    fun refresh() = changed()

    fun up() = moveCursor(-1)
    fun down() = moveCursor(1)
    fun left() = change(-1)
    fun right() = change(1)

    /** Returns true when the menu should close. */
    fun ok(): Boolean {
        host.click()
        if (page == Page.APPS) {
            if (cursor == 0) {
                prefs.order = emptyList()
                apps.clear(); apps += defaults
            } else grabbed = !grabbed
            changed()
            return false
        }
        when (Item.values()[cursor]) {
            Item.APPS -> { page = Page.APPS; cursor = 0; first = 0 }
            Item.LOCATION -> host.askCity()
            Item.HOME -> host.openAccessibilitySettings()
            Item.EXIT -> return true
            else -> return change(1).let { false }
        }
        changed()
        return false
    }

    /** Returns true when the menu should close. */
    fun back(): Boolean {
        host.click()
        if (page == Page.APPS) {
            grabbed = false
            page = Page.MAIN; cursor = Item.APPS.ordinal; first = 0
            changed()
            return false
        }
        return true
    }

    private fun moveCursor(d: Int) {
        val count = if (page == Page.MAIN) Item.values().size else apps.size + 1
        val next = (cursor + d).coerceIn(0, count - 1)
        if (next == cursor) return
        if (page == Page.APPS && grabbed && next >= 1) {
            val a = cursor - 1; val b = next - 1
            apps[a] = apps[b].also { apps[b] = apps[a] }
            prefs.order = apps.map { it.key }
        } else if (page == Page.APPS && grabbed) return
        cursor = next
        host.click()
        changed()
    }

    private fun change(d: Int): Boolean {
        if (page == Page.APPS) {
            if (cursor >= 1) {
                val key = apps[cursor - 1].key
                if (!hidden.remove(key)) hidden += key
                prefs.hidden = hidden.toSet()
                host.click(); changed()
            }
            return false
        }
        when (Item.values()[cursor]) {
            Item.SOUND -> prefs.sound = !prefs.sound
            Item.VOLUME -> prefs.volume = (prefs.volume + d).coerceIn(0, 10)
            Item.WEATHER -> { prefs.weather = !prefs.weather; host.weatherSettingsChanged() }
            Item.LOCATION -> if (prefs.cityName != null) { prefs.cityName = null; host.weatherSettingsChanged() }
            Item.UNITS -> { prefs.fahrenheit = !prefs.fahrenheit; host.weatherSettingsChanged() }
            Item.CLOCK -> prefs.clock24 = !prefs.clock24
            Item.SCANLINES -> prefs.scanlines = !prefs.scanlines
            Item.WOBBLE -> prefs.wobble = !prefs.wobble
            else -> return false
        }
        host.click()
        changed()
        return false
    }

    private fun changed() { version++ }

    fun view(): View {
        if (page == Page.APPS) {
            val rows = ArrayList<Row>(apps.size + 1)
            rows += Row("RESET ORDER (A-Z)")
            apps.forEachIndexed { i, a ->
                val hide = a.key in hidden
                rows += Row("%02d  %s".format(i + 1, a.label.uppercase()), if (hide) "HIDE" else "SHOW", grabbed = grabbed && cursor == i + 1, dim = hide)
            }
            // Keep the cursor inside a ten-row window.
            if (cursor < first) first = cursor
            if (cursor >= first + VISIBLE) first = cursor - VISIBLE + 1
            val window = rows.subList(first, minOf(rows.size, first + VISIBLE))
            return View("APP ORDER", window, cursor - first, if (grabbed) "UP/DOWN MOVE   OK DROP" else "OK PICK UP   LEFT/RIGHT SHOW/HIDE   BACK DONE")
        }
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"
        val location = prefs.cityName?.uppercase() ?: detectedCity?.let { "AUTO · ${it.uppercase()}" } ?: "AUTO"
        val rows = Item.values().map {
            when (it) {
                Item.APPS -> Row("APP ORDER", ">")
                Item.SOUND -> Row("SOUND", onOff(prefs.sound))
                Item.VOLUME -> Row("VOLUME", bar = prefs.volume)
                Item.WEATHER -> Row("WEATHER", onOff(prefs.weather))
                Item.LOCATION -> Row("LOCATION", location, dim = !prefs.weather)
                Item.UNITS -> Row("UNITS", if (prefs.fahrenheit) "°F" else "°C", dim = !prefs.weather)
                Item.CLOCK -> Row("CLOCK", if (prefs.clock24) "24H" else "12H")
                Item.SCANLINES -> Row("SCANLINES", onOff(prefs.scanlines))
                Item.WOBBLE -> Row("TAPE WOBBLE", onOff(prefs.wobble))
                Item.HOME -> Row("HOME BUTTON", onOff(host.homeButtonEnabled()))
                Item.EXIT -> Row("EXIT")
            }
        }
        return View("VHS SETUP", rows, cursor, "UP/DOWN SELECT   LEFT/RIGHT CHANGE   OK ENTER   BACK EXIT")
    }

    private companion object {
        const val VISIBLE = 10
    }
}
