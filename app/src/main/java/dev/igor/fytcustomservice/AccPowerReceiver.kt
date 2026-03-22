package dev.igor.fytcustomservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AccPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceAction = when (intent.action) {
            ACTION_ACC_ON -> FytForegroundService.ACTION_ACC_ON
            ACTION_ACC_OFF -> FytForegroundService.ACTION_ACC_OFF
            else -> null
        } ?: return

        val serviceIntent = Intent(context, FytForegroundService::class.java).apply {
            action = serviceAction
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_ACC_ON = "com.fyt.boot.ACCON"
        const val ACTION_ACC_OFF = "com.fyt.boot.ACCOFF"
    }
}
