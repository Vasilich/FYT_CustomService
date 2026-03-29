package dev.igor.fytcustomservice

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log

object ForegroundAppHelper {
    private const val TAG = "ForegroundAppHelper"

    fun getForegroundPackage(context: Context, excludePackage: String? = null): String? {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val event = UsageEvents.Event()
        var bestPackage: String? = null
        var bestTs = 0L

        // Prefer event-based foreground detection first.
        val events = usageStatsManager.queryEvents(now - EVENT_LOOKBACK_MS, now)
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            val pkg = event.packageName
            if (!isForegroundEvent || pkg.isNullOrBlank()) continue
            if (!excludePackage.isNullOrBlank() && pkg == excludePackage) continue
            if (event.timeStamp >= bestTs) {
                bestTs = event.timeStamp
                bestPackage = pkg
            }
        }
        if (!bestPackage.isNullOrBlank()) {
            return bestPackage
        }

        // Fallback for cases where no recent foreground transition event exists.
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - STATS_LOOKBACK_MS,
            now
        )
        var fallbackPackage: String? = null
        var fallbackTs = 0L
        stats?.forEach { stat ->
            val pkg = stat.packageName ?: return@forEach
            if (pkg.isBlank()) return@forEach
            if (!excludePackage.isNullOrBlank() && pkg == excludePackage) return@forEach
            val ts = stat.lastTimeUsed
            if (ts >= fallbackTs) {
                fallbackTs = ts
                fallbackPackage = pkg
            }
        }
        return fallbackPackage
    }

    fun launchPackage(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        return try {
            context.startActivity(launchIntent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to launch package=$packageName", t)
            false
        }
    }

    private const val EVENT_LOOKBACK_MS = 30 * 60 * 1000L
    private const val STATS_LOOKBACK_MS = 24 * 60 * 60 * 1000L
}
