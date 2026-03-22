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
        val events = usageStatsManager.queryEvents(now - 15_000, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (!event.packageName.isNullOrBlank()) {
                    lastForeground = event.packageName
                }
            }
        }

        if (!excludePackage.isNullOrBlank() && lastForeground == excludePackage) {
            return null
        }
        return lastForeground
    }

    fun launchPackage(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to launch package=$packageName", t)
            false
        }
    }
}
