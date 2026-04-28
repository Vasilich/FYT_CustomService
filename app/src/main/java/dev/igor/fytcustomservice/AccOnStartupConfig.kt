package dev.igor.fytcustomservice

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class AccOnStartupTarget(
    val packageName: String,
    val activityName: String?,
    val pauseAfterMs: Int,
    val enabled: Boolean = true
)

data class InstalledAppEntry(
    val packageName: String,
    val displayName: String
)

data class InstalledActivityEntry(
    val packageName: String,
    val activityName: String,
    val displayName: String
)

data class RunningCheckResult(
    val isRunning: Boolean,
    val source: String
)

object AccOnStartupStore {
    private const val PREFS_NAME = "fyt_custom_service_acc_on_targets"
    private const val KEY_TARGETS_JSON = "targets_json"
    private const val DEFAULT_PAUSE_MS = 1500
    private const val MIN_PAUSE_MS = 0
    private const val MAX_PAUSE_MS = 15000

    fun load(context: Context): List<AccOnStartupTarget> {
        val raw = prefs(context).getString(KEY_TARGETS_JSON, null) ?: return emptyList()
        return runCatching {
            val out = mutableListOf<AccOnStartupTarget>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName")
                val activityRaw = obj.optString("activityName")
                val activity = activityRaw.takeIf { it.isNotBlank() }
                if (pkg.isBlank()) continue
                val pauseMs = obj.optInt("pauseAfterMs", DEFAULT_PAUSE_MS)
                    .coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
                val enabled = obj.optBoolean("enabled", true)
                out += AccOnStartupTarget(pkg, activity, pauseMs, enabled)
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, targets: List<AccOnStartupTarget>) {
        val arr = JSONArray()
        targets.forEach { target ->
            arr.put(
                JSONObject()
                    .put("packageName", target.packageName)
                    .put("activityName", target.activityName ?: "")
                    .put("pauseAfterMs", target.pauseAfterMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS))
                    .put("enabled", target.enabled)
            )
        }
        prefs(context).edit().putString(KEY_TARGETS_JSON, arr.toString()).apply()
    }

    fun parsePauseMs(rawValue: String): Int {
        val parsed = rawValue.trim().toIntOrNull() ?: DEFAULT_PAUSE_MS
        return parsed.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
    }

    private fun prefs(context: Context) =
        AppStorage.sharedPreferences(context, PREFS_NAME)
}

object InstalledAppCatalog {
    fun queryLaunchableApps(context: Context): List<InstalledAppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return infos
            .map {
                val pkg = it.activityInfo.packageName
                val label = it.loadLabel(pm).toString().ifBlank { pkg }
                InstalledAppEntry(packageName = pkg, displayName = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.displayName.lowercase() }
    }

    fun queryExportedActivities(context: Context, packageName: String): List<InstalledActivityEntry> {
        val pm = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        }

        val activities = packageInfo.activities.orEmpty()
        return activities
            .filter { it.exported }
            .map { info ->
                val component = ComponentName(packageName, info.name)
                val label = info.loadLabel(pm).toString().ifBlank { component.shortClassName }
                InstalledActivityEntry(
                    packageName = packageName,
                    activityName = info.name,
                    displayName = label
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }
}

object StartupTargetLauncher {
    fun launchActivity(context: Context, target: AccOnStartupTarget): Boolean {
        val intent = if (target.activityName.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(target.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } else {
            Intent().apply {
                component = ComponentName(target.packageName, target.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } ?: return false

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun checkPackageLikelyRunning(context: Context, packageName: String): RunningCheckResult {
        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val processes = am.runningAppProcesses ?: emptyList()
        val processMatch = processes.any { process ->
            process.pkgList?.any { pkg -> pkg == packageName } == true
        }
        if (processMatch) {
            return RunningCheckResult(
                isRunning = true,
                source = "running_app_processes"
            )
        }

        val foregroundNow = ForegroundAppHelper.getForegroundPackage(
            context = context,
            excludePackage = null
        )
        if (foregroundNow == packageName) {
            return RunningCheckResult(
                isRunning = true,
                source = "foreground_usage_event"
            )
        }

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        if (usageStatsManager == null) {
            return RunningCheckResult(
                isRunning = false,
                source = "no_usage_stats_manager"
            )
        }

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - RUNNING_EVENT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var lastState: Int? = null
        var lastStateTs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    lastState = STATE_FOREGROUND
                    lastStateTs = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    lastState = STATE_BACKGROUND
                    lastStateTs = event.timeStamp
                }
            }
        }

        if (lastState == STATE_FOREGROUND && now - lastStateTs <= RUNNING_EVENT_LOOKBACK_MS) {
            return RunningCheckResult(
                isRunning = true,
                source = "recent_foreground_without_background"
            )
        }

        return RunningCheckResult(
            isRunning = false,
            source = "not_detected"
        )
    }

    fun isPackageLikelyRunning(context: Context, packageName: String): Boolean {
        return checkPackageLikelyRunning(context, packageName).isRunning
    }

    private const val RUNNING_EVENT_LOOKBACK_MS = 2 * 60 * 1000L
    private const val STATE_FOREGROUND = 1
    private const val STATE_BACKGROUND = 2
}
