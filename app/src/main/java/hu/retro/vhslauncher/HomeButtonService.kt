package hu.retro.vhslauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps VHS Launcher as the home screen on Google TV while the Google TV home app stays enabled
 * for its side panel (profile, quick settings, notifications), which only that app can show.
 *
 * - Home pressed briefly opens VHS Launcher.
 * - Home held opens the Google TV panel through the system's own "expand notifications" path.
 * - If the Google TV home screen comes up anyway (Back out of an app, after boot), it is replaced.
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false

    private val longPress = Runnable {
        longPressFired = true
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    override fun onServiceConnected() {
        // Right after boot the system starts the Google TV home; take over from it.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openLauncher()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longPressFired = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!longPressFired) openLauncher()
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == GOOGLE_TV_HOME_PACKAGE &&
            event.className?.toString() == GOOGLE_TV_HOME_ACTIVITY
        ) openLauncher()
    }

    override fun onInterrupt() = handler.removeCallbacks(longPress)

    private fun openLauncher() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    private companion object {
        const val LONG_PRESS_MS = 550L
        const val BOOT_WINDOW_MS = 3 * 60_000L
        const val GOOGLE_TV_HOME_PACKAGE = "com.google.android.apps.tv.launcherx"
        const val GOOGLE_TV_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
    }
}
