package dev.igor.fytcustomservice

import android.app.ActivityManager
import android.content.Context

object ServiceRuntimeHelper {
    fun isForegroundServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(Int.MAX_VALUE)
        return services.any { it.service.className == FytForegroundService::class.java.name }
    }
}
