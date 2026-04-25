package dev.igor.fytcustomservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AccPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action ?: return
        val serviceAction = when (intent.action) {
            ACTION_ACC_ON -> FytForegroundService.ACTION_ACC_ON
            ACTION_ACC_OFF -> FytForegroundService.ACTION_ACC_OFF
            else -> null
        } ?: return

        if (receivedAction == ACTION_ACC_ON) {
            AccEventStateStore.setLastAccOnTimestamp(context)
        } else if (receivedAction == ACTION_ACC_OFF) {
            AccEventStateStore.setLastAccOffTimestamp(context)
        }
        AccEventLog.append(
            context,
            "AccPowerReceiver received action=$receivedAction and forwarding to service"
        )

        val startContext = AppStorage.componentStartContext(context)
        val serviceIntent = Intent(startContext, FytForegroundService::class.java).apply {
            action = serviceAction
            putExtra(
                FytForegroundService.EXTRA_TRIGGER_SOURCE,
                FytForegroundService.TRIGGER_SOURCE_ACC_ON_INTENT
            )
        }
        try {
            ContextCompat.startForegroundService(startContext, serviceIntent)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "startForegroundService failed for action=$receivedAction, trying startService",
                t
            )
            AccEventLog.append(
                context,
                "AccPowerReceiver startForegroundService failed action=$receivedAction reason=${t.javaClass.simpleName}; fallback startService"
            )
            runCatching { startContext.startService(serviceIntent) }
                .onFailure { err ->
                    Log.e(TAG, "startService fallback failed for action=$receivedAction", err)
                    AccEventLog.append(
                        context,
                        "AccPowerReceiver startService fallback failed action=$receivedAction reason=${err.javaClass.simpleName}"
                    )
                }
        }
    }

    companion object {
        const val ACTION_ACC_ON = "com.fyt.boot.ACCON"
        const val ACTION_ACC_OFF = "com.fyt.boot.ACCOFF"
        private const val TAG = "AccPowerReceiver"
    }
}
