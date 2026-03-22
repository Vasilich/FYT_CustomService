package dev.igor.fytcustomservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ServiceSettings.autoStartOnBoot(context)) return

        val shouldStart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!shouldStart) return

        val serviceIntent = Intent(context, FytForegroundService::class.java).apply {
            action = FytForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
