package dev.igor.fytcustomservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class FytForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAccOnRunnable: Runnable? = null
    private val pendingStartupRunnables = mutableListOf<Runnable>()

    override fun onCreate() {
        super.onCreate()
        WatchdogScheduler.ensureScheduled(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Service active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchdogScheduler.ensureScheduled(this)
        val triggerSource = intent?.getStringExtra(EXTRA_TRIGGER_SOURCE).orEmpty().ifBlank {
            TRIGGER_SOURCE_UNKNOWN
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_EXECUTE -> {
                val code = intent.getIntExtra(EXTRA_COMMAND_CODE, -1)
                val arg1 = intent.getIntExtra(EXTRA_ARG1, 0)
                executeCommand(code, arg1)
            }

            ACTION_ACC_OFF -> handleAccOff()
            ACTION_ACC_ON -> handleAccOn(triggerSource)
            ACTION_RESET_STATE -> handleResetState()
            ACTION_START -> {
                if (triggerSource == TRIGGER_SOURCE_FYT_STARTUP_MANAGER) {
                    AccEventLog.append(
                        this,
                        "ACTION_START from FYT startup manager; running ACCON-equivalent startup flow"
                    )
                    handleAccOn(triggerSource)
                } else {
                    AccEventLog.append(this, "ACTION_START received source=$triggerSource")
                }
            }
            null -> AccEventLog.append(this, "Service restarted with null action source=$triggerSource")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pendingAccOnRunnable?.let(handler::removeCallbacks)
        pendingAccOnRunnable = null
        clearPendingStartupRunnables()
        super.onDestroy()
    }

    private fun handleAccOff() {
        AccEventStateStore.setLastAccOffTimestamp(this)
        AccEventLog.append(this, "RECEIVED com.fyt.boot.ACCOFF")

        val snapshot = MediaControlHelper.captureCurrentMediaSnapshot(this)
        if (snapshot == null) {
            updateStatus("ACCOFF: no active media session")
            AccEventLog.append(this, "ACCOFF no active media session detected")
            AccEventStateStore.setLastSavedPlayer(this, null)
            AccEventStateStore.setLastSavedPlayerState(this, null)
            return
        }

        val persisted = MediaStateStore.saveAccOffState(
            context = this,
            packageName = snapshot.packageName
        )
        val playerStateBeforePause = snapshot.playerState
        AccEventStateStore.setLastSavedPlayer(this, snapshot.packageName)
        AccEventStateStore.setLastSavedPlayerState(this, playerStateBeforePause)

        MediaControlHelper.sendPause(this, snapshot.packageName)
        AccEventLog.append(
            this,
            "ACCOFF activePlayer=${snapshot.packageName} stateBeforePause=$playerStateBeforePause " +
                "persisted=$persisted pauseSent=true"
        )

        Log.i(
            TAG,
            "ACCOFF saved package=${snapshot.packageName}, persisted=$persisted"
        )
        updateStatus("ACCOFF: ${snapshot.packageName} saved, PAUSE sent")
    }

    private fun handleAccOn(triggerSource: String) {
        AccEventStateStore.setLastAccOnTimestamp(this)
        AccEventLog.append(this, "RECEIVED ACCON source=$triggerSource")

        val foregroundBeforeTargets = ForegroundAppHelper.getForegroundPackage(
            context = this,
            excludePackage = null
        )
        AccEventStateStore.setLastActiveAppBeforeStartupTargets(this, foregroundBeforeTargets)
        AccEventLog.append(
            this,
            "ACCON detected previousForegroundBeforeStartupTargets=${foregroundBeforeTargets ?: "[none]"}"
        )

        val saved = MediaStateStore.loadAccOffState(this)
        if (saved == null) {
            updateStatus("ACCON: no saved player, running startup targets")
            AccEventLog.append(this, "ACCON no saved ACCOFF player state; running startup targets only")
            runConfiguredStartupTargets(restorePackage = foregroundBeforeTargets, triggerSource = triggerSource) {
                updateStatus("ACCON done: startup targets only")
            }
            return
        }

        val launched = ForegroundAppHelper.launchPackage(this, saved.packageName)
        AccEventLog.append(
            this,
            "ACCON savedPlayer=${saved.packageName} launchResult=$launched"
        )
        if (!launched) {
            updateStatus("ACCON: failed to launch ${saved.packageName}")
            Log.w(TAG, "ACCON failed to launch ${saved.packageName}")
            AccEventStateStore.setLastStartedPlayer(this, saved.packageName)
            AccEventLog.append(
                this,
                "ACCON saved player launch failed; running startup targets anyway"
            )
            runConfiguredStartupTargets(restorePackage = foregroundBeforeTargets, triggerSource = triggerSource) {
                updateStatus("ACCON done: startup targets only (saved launch failed)")
            }
            return
        }
        AccEventStateStore.setLastStartedPlayer(this, saved.packageName)

        val delayMs = ServiceSettings.accOnPlayDelayMs(this)
        updateStatus("ACCON: launched ${saved.packageName}, waiting ${delayMs}ms")

        pendingAccOnRunnable?.let(handler::removeCallbacks)
        pendingAccOnRunnable = null
        clearPendingStartupRunnables()
        val run = Runnable {
            MediaControlHelper.sendPlay(this, saved.packageName)
            Log.i(TAG, "ACCON PLAY sent to ${saved.packageName}")
            AccEventLog.append(this, "ACCON sent PLAY to saved player ${saved.packageName} (always)")

            runConfiguredStartupTargets(restorePackage = foregroundBeforeTargets, triggerSource = triggerSource) {
                MediaStateStore.clearAccOffState(this)
                AccEventLog.append(this, "ACCON cleared saved ACCOFF player state")
                updateStatus("ACCON done: ${saved.packageName}")
                pendingAccOnRunnable = null
            }
        }
        pendingAccOnRunnable = run
        handler.postDelayed(run, delayMs.toLong())
    }

    private fun runConfiguredStartupTargets(
        restorePackage: String?,
        triggerSource: String,
        onDone: () -> Unit
    ) {
        val targets = AccOnStartupStore.load(this)
        AccEventLog.append(this, "STARTUP_LIST triggerSource=$triggerSource")
        if (targets.isEmpty()) {
            AccEventLog.append(this, "ACCON startup targets: none configured")
            restoreForegroundPackage(restorePackage)
            onDone()
            return
        }
        AccEventLog.append(this, "ACCON startup targets count=${targets.size}")

        fun scheduleNext(index: Int) {
            if (index >= targets.size) {
                AccEventLog.append(this, "ACCON startup targets completed")
                restoreForegroundPackage(restorePackage)
                onDone()
                return
            }

            val target = targets[index]
            if (!target.enabled) {
                AccEventLog.append(
                    this,
                    "ACCON target[$index] skipped package=${target.packageName} activity=${target.activityName ?: "[default]"} " +
                        "reason=disabled"
                )
                scheduleNext(index + 1)
                return
            }
            val alreadyRunning = StartupTargetLauncher.isPackageLikelyRunning(this, target.packageName)
            if (alreadyRunning) {
                Log.i(
                    TAG,
                    "ACCON startup target skipped (already running): ${target.packageName}/${target.activityName}"
                )
                AccEventLog.append(
                    this,
                    "ACCON target[$index] skipped package=${target.packageName} activity=${target.activityName ?: "[default]"} " +
                        "reason=already_running"
                )
                scheduleNext(index + 1)
                return
            }

            val launched = StartupTargetLauncher.launchActivity(this, target)
            Log.i(
                TAG,
                "ACCON startup target launch package=${target.packageName} activity=${target.activityName} " +
                    "pause=${target.pauseAfterMs}ms result=$launched"
            )
            AccEventLog.append(
                this,
                "ACCON target[$index] launch package=${target.packageName} activity=${target.activityName ?: "[default]"} " +
                    "pause=${target.pauseAfterMs}ms result=$launched"
            )

            val next = object : Runnable {
                override fun run() {
                    pendingStartupRunnables.remove(this)
                    scheduleNext(index + 1)
                }
            }
            pendingStartupRunnables += next
            handler.postDelayed(next, target.pauseAfterMs.toLong())
        }

        scheduleNext(0)
    }

    private fun restoreForegroundPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) {
            val homeLaunched = launchHomeScreen()
            AccEventLog.append(
                this,
                "ACCON restore previousForeground missing; launched HOME result=$homeLaunched"
            )
            return
        }
        val restored = ForegroundAppHelper.launchPackage(this, packageName)
        Log.i(TAG, "ACCON restore foreground package=$packageName result=$restored")
        AccEventLog.append(
            this,
            "ACCON restore previousForeground=$packageName result=$restored"
        )
        // Some FYT launchers/apps race and bring the last target back on top.
        // Re-assert restore shortly after to keep the expected foreground app.
        handler.postDelayed({ ForegroundAppHelper.launchPackage(this, packageName) }, 500L)
        handler.postDelayed({ ForegroundAppHelper.launchPackage(this, packageName) }, 1500L)
        handler.postDelayed({ ForegroundAppHelper.launchPackage(this, packageName) }, 3000L)
    }

    private fun launchHomeScreen(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(homeIntent)
            true
        }.getOrElse { err ->
            Log.w(TAG, "Failed to launch HOME screen", err)
            false
        }
    }

    private fun clearPendingStartupRunnables() {
        pendingStartupRunnables.forEach(handler::removeCallbacks)
        pendingStartupRunnables.clear()
    }

    private fun handleResetState() {
        MediaStateStore.clearAccOffState(this)
        AccEventStateStore.clear(this)
        AccEventLog.append(this, "RESET state requested: cleared ACC timestamps/player markers/saved ACCOFF state")
        updateStatus("State reset complete")
    }

    private fun executeCommand(code: Int, arg1: Int) {
        when (code) {
            1 -> Log.i(TAG, "Command 1 executed, arg1=$arg1")
            2 -> Log.i(TAG, "Command 2 executed, arg1=$arg1")
            else -> Log.w(TAG, "Unknown command: $code arg1=$arg1")
        }

        updateStatus("Last command=$code arg1=$arg1")
    }

    private fun updateStatus(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FYT custom service")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FYT custom service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps background service alive"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "dev.igor.fytcustomservice.START"
        const val ACTION_STOP = "dev.igor.fytcustomservice.STOP"
        const val ACTION_EXECUTE = "dev.igor.fytcustomservice.EXECUTE"
        const val ACTION_ACC_ON = "dev.igor.fytcustomservice.ACC_ON"
        const val ACTION_ACC_OFF = "dev.igor.fytcustomservice.ACC_OFF"
        const val ACTION_RESET_STATE = "dev.igor.fytcustomservice.RESET_STATE"

        const val EXTRA_COMMAND_CODE = "extra_command_code"
        const val EXTRA_ARG1 = "extra_arg1"
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"

        const val TRIGGER_SOURCE_ACC_ON_INTENT = "acc_on_intent"
        const val TRIGGER_SOURCE_BOOT_COMPLETED = "boot_completed"
        const val TRIGGER_SOURCE_FYT_STARTUP_MANAGER = "fyt_startup_manager"
        const val TRIGGER_SOURCE_BOOT_MISC = "boot_misc"
        private const val TRIGGER_SOURCE_UNKNOWN = "unknown"

        private const val TAG = "FytForegroundService"
        private const val CHANNEL_ID = "fyt_custom_service_channel"
        private const val NOTIFICATION_ID = 7870
    }
}
