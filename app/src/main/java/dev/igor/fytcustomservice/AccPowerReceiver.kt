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
            ACTION_ACC_ON, ACTION_GLSX_ACC_ON -> FytForegroundService.ACTION_ACC_ON
            ACTION_ACC_OFF, ACTION_GLSX_ACC_OFF -> FytForegroundService.ACTION_ACC_OFF
            else -> null
        } ?: return

        if (receivedAction == ACTION_ACC_ON || receivedAction == ACTION_GLSX_ACC_ON) {
            AccEventStateStore.setLastAccOnTimestamp(context)
        } else if (receivedAction == ACTION_ACC_OFF || receivedAction == ACTION_GLSX_ACC_OFF) {
            AccEventStateStore.setLastAccOffTimestamp(context)
        }
        AccEventLog.append(
            context,
            "AccPowerReceiver received action=$receivedAction and forwarding to service"
        )

        val serviceIntent = Intent(context, FytForegroundService::class.java).apply {
            action = serviceAction
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
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
            runCatching { context.startService(serviceIntent) }
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
        const val ACTION_GLSX_ACC_ON = "com.glsx.boot.ACCON"
        const val ACTION_GLSX_ACC_OFF = "com.glsx.boot.ACCOFF"
        private const val TAG = "AccPowerReceiver"
    }
}
