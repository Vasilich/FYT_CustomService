package dev.igor.fytcustomservice

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class StartupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WatchdogScheduler.ensureScheduled(this)

        if (!ServiceRuntimeHelper.isForegroundServiceRunning(this)) {
            val intent = Intent(this, FytForegroundService::class.java).apply {
                action = FytForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}
