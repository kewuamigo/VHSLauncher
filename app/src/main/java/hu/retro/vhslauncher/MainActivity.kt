package hu.retro.vhslauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity(), SceneRenderer.Host, SetupMenu.Host {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SceneRenderer
    private lateinit var prefs: Prefs
    private lateinit var sfx: Sfx
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var longPressHandled = false
    private var weatherFetchedAt = 0L

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            main.postDelayed(this, WEATHER_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sfx = Sfx(this, prefs)
        renderer = SceneRenderer(Art(this), this, prefs, sfx, SetupMenu(prefs, this))
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(surface)
        hideSystemBars()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        surface.onResume()
        surface.requestFocus()
        reloadApps()
        surface.queueEvent { renderer.onHostResume() }
        val since = SystemClock.elapsedRealtime() - weatherFetchedAt
        main.removeCallbacks(weatherTick)
        main.postDelayed(weatherTick, if (weatherFetchedAt == 0L || since >= WEATHER_INTERVAL_MS) 0L else WEATHER_INTERVAL_MS - since)
    }

    override fun onPause() {
        main.removeCallbacks(weatherTick)
        surface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        loader.shutdownNow()
        sfx.release()
        super.onDestroy()
    }

    private fun reloadApps() {
        loader.execute {
            val apps = AppRepository.load(applicationContext)
            surface.queueEvent { renderer.setApps(apps) }
        }
    }

    private fun refreshWeather() {
        if (!prefs.weather) { surface.queueEvent { renderer.setWeather(null) }; return }
        loader.execute {
            val w = WeatherService.fetch(prefs)
            if (w != null) weatherFetchedAt = SystemClock.elapsedRealtime()
            surface.queueEvent { renderer.setWeather(w) }
        }
    }

    // ------------------------------------------------------------ remote control

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> send { move(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> send { move(1) }
            KeyEvent.KEYCODE_DPAD_UP -> send { vertical(-1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> send { vertical(1) }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MOVE_HOME -> send { move(-5) }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MOVE_END -> send { move(5) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) event.startTracking()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> send { play() }
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_EJECT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> send { stop() }
            KeyEvent.KEYCODE_MENU -> send { details() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isSelectKey(keyCode)) {
            longPressHandled = true
            send { details() }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isSelectKey(keyCode)) {
            if (!longPressHandled && !event.isCanceled) send { play() }
            longPressHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isSelectKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val f = event.x / surface.width.coerceAtLeast(1)
            when {
                f < 0.33f -> send { move(-1) }
                f > 0.67f -> send { move(1) }
                else -> send { play() }
            }
        }
        return true
    }

    private inline fun send(crossinline action: SceneRenderer.() -> Unit) {
        surface.queueEvent { renderer.action() }
    }

    // ------------------------------------------------------------ SceneRenderer.Host

    override fun launch(item: LaunchItem) = runOnUiThread {
        try {
            startActivity(item.intent)
        } catch (e: Exception) {
            surface.queueEvent { renderer.launchFailed() }
        }
    }

    override fun showDetails(item: LaunchItem) = runOnUiThread {
        val pkg = item.packageName ?: return@runOnUiThread
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ SetupMenu.Host

    override fun askCity() = runOnUiThread {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Budapest"
            setText(prefs.cityName.orEmpty())
            setSingleLine()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather location")
            .setMessage("Type a city. Leave it empty to detect it from your internet connection.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> saveCity(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCity(name: String) {
        if (name.isBlank()) {
            prefs.cityName = null
            weatherSettingsChanged()
            return
        }
        loader.execute {
            val place = WeatherService.geocode(name)
            runOnUiThread {
                if (place == null) {
                    Toast.makeText(this, "Couldn't find \"$name\"", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.cityName = place.name
                    prefs.cityLat = place.lat.toFloat()
                    prefs.cityLon = place.lon.toFloat()
                    weatherSettingsChanged()
                }
            }
        }
    }

    override fun openAccessibilitySettings() = runOnUiThread {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun homeButtonEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    override fun weatherSettingsChanged() {
        weatherFetchedAt = 0L
        refreshWeather()
        surface.queueEvent { renderer.settingsChanged() }
    }

    override fun click() = sfx.play(Sfx.Sound.SWITCH, vary = true)

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private companion object {
        const val WEATHER_INTERVAL_MS = 30 * 60_000L
    }
}
