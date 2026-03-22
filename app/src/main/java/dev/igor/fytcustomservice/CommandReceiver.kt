package dev.igor.fytcustomservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val expectedAction = ServiceSettings.commandAction(context)
        if (intent.action != expectedAction) return

        val serviceIntent = Intent(context, FytForegroundService::class.java).apply {
            action = FytForegroundService.ACTION_EXECUTE
            putExtra(
                FytForegroundService.EXTRA_COMMAND_CODE,
                intent.getIntExtra(FytForegroundService.EXTRA_COMMAND_CODE, -1)
            )
            putExtra(
                FytForegroundService.EXTRA_ARG1,
                intent.getIntExtra(FytForegroundService.EXTRA_ARG1, 0)
            )
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
