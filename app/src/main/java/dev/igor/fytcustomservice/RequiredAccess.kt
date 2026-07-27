package dev.igor.fytcustomservice

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object RequiredAccess {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component != null &&
                component.packageName == context.packageName &&
                component.className == FytNotificationListenerService::class.java.name
        }
    }
}
