package dev.igor.fytcustomservice

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object WatchdogScheduler {
    private const val UNIQUE_WORK_NAME = "fyt_custom_service_watchdog"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }
}

class ServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        WatchdogScheduler.ensureScheduled(applicationContext)
        if (!ServiceRuntimeHelper.isForegroundServiceRunning(applicationContext)) {
            val intent = Intent(applicationContext, FytForegroundService::class.java).apply {
                action = FytForegroundService.ACTION_START
                putExtra(
                    FytForegroundService.EXTRA_TRIGGER_SOURCE,
                    FytForegroundService.TRIGGER_SOURCE_WATCHDOG
                )
            }
            ContextCompat.startForegroundService(applicationContext, intent)
        }
        return Result.success()
    }
}
