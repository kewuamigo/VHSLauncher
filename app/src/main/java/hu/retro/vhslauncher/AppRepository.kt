package hu.retro.vhslauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** One cassette on the shelf: something the launcher can start, or the launcher's own setup tape. */
class LaunchItem(
    val label: String,
    val packageName: String?,
    val intent: Intent?,
    val key: String,
) {
    val isSetup get() = key == SETUP_KEY

    companion object {
        const val SETUP_KEY = "vhs/setup"
        val SETUP = LaunchItem("Setup", null, null, SETUP_KEY)
    }
}

object AppRepository {

    /** Every launchable app: TV apps first (leanback launcher), then regular launcher apps, A-Z. */
    fun load(context: Context): List<LaunchItem> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, LaunchItem>()

        fun collect(category: String) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || byPackage.containsKey(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, activity.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val label = info.loadLabel(pm).toString().trim().ifEmpty { pkg }
                byPackage[pkg] = LaunchItem(label, pkg, launch, "$pkg/${activity.name}")
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)

        val apps = byPackage.values.sortedBy { it.label.lowercase() }.toMutableList()
        // Most TVs already list their own Settings app; only add ours when none is there.
        val hasSettings = apps.any { it.label.equals("Settings", ignoreCase = true) || it.packageName?.endsWith(".settings") == true }
        if (!hasSettings) apps += LaunchItem(
            label = "Settings",
            packageName = null,
            intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            key = "system/settings",
        )
        return apps
    }

    /** All apps in the user's order: saved order first, apps installed since then after it in A-Z order. */
    fun ordered(all: List<LaunchItem>, prefs: Prefs): List<LaunchItem> {
        val rank = prefs.order.withIndex().associate { it.value to it.index }
        val (known, fresh) = all.partition { rank.containsKey(it.key) }
        return known.sortedBy { rank[it.key] } + fresh
    }

    /** What goes on the shelf: visible apps in order, then the setup tape. */
    fun shelf(all: List<LaunchItem>, prefs: Prefs): List<LaunchItem> {
        val hidden = prefs.hidden
        return ordered(all, prefs).filterNot { it.key in hidden } + LaunchItem.SETUP
    }
}
