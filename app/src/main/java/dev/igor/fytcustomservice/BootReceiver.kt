package dev.igor.fytcustomservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WatchdogScheduler.ensureScheduled(context)
        if (!ServiceSettings.autoStartOnBoot(context)) return

        val action = intent.action
        val shouldStart = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!shouldStart) return

        val startContext = resolveStartContext(context)
        val serviceIntent = Intent(startContext, FytForegroundService::class.java).apply {
            this.action = if (action == Intent.ACTION_BOOT_COMPLETED) {
                FytForegroundService.ACTION_ACC_ON
            } else {
                FytForegroundService.ACTION_START
            }
            putExtra(
                FytForegroundService.EXTRA_TRIGGER_SOURCE,
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    FytForegroundService.TRIGGER_SOURCE_BOOT_COMPLETED
                } else {
                    FytForegroundService.TRIGGER_SOURCE_BOOT_MISC
                }
            )
        }
        ContextCompat.startForegroundService(startContext, serviceIntent)
    }

    private fun resolveStartContext(context: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context
        val um = context.getSystemService(UserManager::class.java)
        return if (um != null && !um.isUserUnlocked) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }
}
