package dev.igor.fytcustomservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class FytForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAccOffHandledAtMs = 0L
    private var lastAccOnHandledAtMs = 0L
    private var pendingAccOnRunnable: Runnable? = null
    private val pendingStartupRunnables = mutableListOf<Runnable>()
    private var runtimeAccReceiverRegistered = false
    private val runtimeAccReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val receivedAction = intent.action ?: return
            val serviceAction = when (receivedAction) {
                AccPowerReceiver.ACTION_ACC_ON, AccPowerReceiver.ACTION_GLSX_ACC_ON -> ACTION_ACC_ON
                AccPowerReceiver.ACTION_ACC_OFF, AccPowerReceiver.ACTION_GLSX_ACC_OFF -> ACTION_ACC_OFF
                else -> null
            } ?: return
            AccEventLog.append(
                this@FytForegroundService,
                "Runtime ACC receiver received action=$receivedAction and forwarding to service"
            )
            startService(
                Intent(this@FytForegroundService, FytForegroundService::class.java).apply {
                    action = serviceAction
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        WatchdogScheduler.ensureScheduled(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Service active"))
        registerRuntimeAccReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchdogScheduler.ensureScheduled(this)
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
            ACTION_ACC_ON -> handleAccOn()
            ACTION_START, null -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pendingAccOnRunnable?.let(handler::removeCallbacks)
        pendingAccOnRunnable = null
        clearPendingStartupRunnables()
        unregisterRuntimeAccReceiver()
        super.onDestroy()
    }

    private fun handleAccOff() {
        AccEventStateStore.setLastAccOffTimestamp(this)
        AccEventLog.append(this, "RECEIVED com.fyt.boot.ACCOFF")

        if (isDuplicateAccEvent(isAccOn = false)) {
            Log.i(TAG, "ACCOFF ignored as duplicate signal")
            AccEventLog.append(this, "ACCOFF ignored: duplicate signal")
            return
        }

        withShortWakeLock {
            val snapshot = MediaControlHelper.captureCurrentMediaSnapshot(this)
            if (snapshot == null) {
                updateStatus("ACCOFF: no active media session")
                AccEventLog.append(this, "ACCOFF no active media session detected")
                return@withShortWakeLock
            }

            val persisted = MediaStateStore.saveAccOffState(
                context = this,
                packageName = snapshot.packageName,
                wasPlaying = snapshot.wasPlaying
            )
            AccEventStateStore.setLastSavedPlayer(this, snapshot.packageName)

            MediaControlHelper.sendPause(this, snapshot.packageName)
            AccEventLog.append(
                this,
                "ACCOFF activePlayer=${snapshot.packageName} wasPlaying=${snapshot.wasPlaying} " +
                    "persisted=$persisted pauseSent=true"
            )

            Log.i(
                TAG,
                "ACCOFF saved package=${snapshot.packageName}, wasPlaying=${snapshot.wasPlaying}, persisted=$persisted"
            )
            updateStatus("ACCOFF: ${snapshot.packageName} saved, PAUSE sent")
        }
    }

    private fun handleAccOn() {
        AccEventStateStore.setLastAccOnTimestamp(this)
        AccEventLog.append(this, "RECEIVED com.fyt.boot.ACCON")

        if (isDuplicateAccEvent(isAccOn = true)) {
            Log.i(TAG, "ACCON ignored as duplicate signal")
            AccEventLog.append(this, "ACCON ignored: duplicate signal")
            return
        }

        withShortWakeLock {
            val saved = MediaStateStore.loadAccOffState(this)
            if (saved == null) {
                updateStatus("ACCON: nothing saved from ACCOFF")
                AccEventLog.append(this, "ACCON no saved ACCOFF player state")
                return@withShortWakeLock
            }

            val previousForeground = ForegroundAppHelper.getForegroundPackage(
                context = this,
                excludePackage = packageName
            )
            val launched = ForegroundAppHelper.launchPackage(this, saved.packageName)
            AccEventLog.append(
                this,
                "ACCON savedPlayer=${saved.packageName} wasPlaying=${saved.wasPlaying} " +
                    "launchResult=$launched"
            )
            if (!launched) {
                updateStatus("ACCON: failed to launch ${saved.packageName}")
                Log.w(TAG, "ACCON failed to launch ${saved.packageName}")
                return@withShortWakeLock
            }
            AccEventStateStore.setLastStartedPlayer(this, saved.packageName)

            val delayMs = ServiceSettings.accOnPlayDelayMs(this)
            updateStatus("ACCON: launched ${saved.packageName}, waiting ${delayMs}ms")

            pendingAccOnRunnable?.let(handler::removeCallbacks)
            pendingAccOnRunnable = null
            clearPendingStartupRunnables()
            val run = Runnable {
                withShortWakeLock {
                    if (saved.wasPlaying) {
                        MediaControlHelper.sendPlay(this, saved.packageName)
                        Log.i(TAG, "ACCON PLAY sent to ${saved.packageName}")
                        AccEventLog.append(this, "ACCON sent PLAY to saved player ${saved.packageName}")
                    } else {
                        AccEventLog.append(
                            this,
                            "ACCON skipped PLAY for saved player ${saved.packageName} because wasPlaying=false"
                        )
                    }

                    runConfiguredStartupTargets {
                        if (!previousForeground.isNullOrBlank() && previousForeground != saved.packageName) {
                            val restored = ForegroundAppHelper.launchPackage(this, previousForeground)
                            Log.i(
                                TAG,
                                "ACCON restore foreground package=$previousForeground result=$restored"
                            )
                            AccEventLog.append(
                                this,
                                "ACCON restore previousForeground=$previousForeground result=$restored"
                            )
                        } else {
                            AccEventLog.append(
                                this,
                                "ACCON restore previousForeground skipped (none or same as saved player)"
                            )
                        }

                        MediaStateStore.clearAccOffState(this)
                        AccEventLog.append(this, "ACCON cleared saved ACCOFF player state")
                        updateStatus("ACCON done: ${saved.packageName}")
                        pendingAccOnRunnable = null
                    }
                }
            }
            pendingAccOnRunnable = run
            handler.postDelayed(run, delayMs.toLong())
        }
    }

    private fun runConfiguredStartupTargets(onDone: () -> Unit) {
        val targets = AccOnStartupStore.load(this)
        if (targets.isEmpty()) {
            AccEventLog.append(this, "ACCON startup targets: none configured")
            onDone()
            return
        }
        AccEventLog.append(this, "ACCON startup targets count=${targets.size}")

        fun scheduleNext(index: Int) {
            if (index >= targets.size) {
                AccEventLog.append(this, "ACCON startup targets completed")
                onDone()
                return
            }

            val target = targets[index]
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

    private fun clearPendingStartupRunnables() {
        pendingStartupRunnables.forEach(handler::removeCallbacks)
        pendingStartupRunnables.clear()
    }

    private fun registerRuntimeAccReceiver() {
        if (runtimeAccReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AccPowerReceiver.ACTION_ACC_ON)
            addAction(AccPowerReceiver.ACTION_ACC_OFF)
            addAction(AccPowerReceiver.ACTION_GLSX_ACC_ON)
            addAction(AccPowerReceiver.ACTION_GLSX_ACC_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(runtimeAccReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(runtimeAccReceiver, filter)
        }
        runtimeAccReceiverRegistered = true
        AccEventLog.append(this, "Runtime ACC receiver registered")
    }

    private fun unregisterRuntimeAccReceiver() {
        if (!runtimeAccReceiverRegistered) return
        runCatching { unregisterReceiver(runtimeAccReceiver) }
        runtimeAccReceiverRegistered = false
        AccEventLog.append(this, "Runtime ACC receiver unregistered")
    }

    private fun isDuplicateAccEvent(isAccOn: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = if (isAccOn) lastAccOnHandledAtMs else lastAccOffHandledAtMs
        val duplicate = now - last < ACC_EVENT_DEBOUNCE_MS
        if (!duplicate) {
            if (isAccOn) {
                lastAccOnHandledAtMs = now
            } else {
                lastAccOffHandledAtMs = now
            }
        }
        return duplicate
    }

    private inline fun withShortWakeLock(block: () -> Unit) {
        val pm = getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:acc_flow")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
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

        const val EXTRA_COMMAND_CODE = "extra_command_code"
        const val EXTRA_ARG1 = "extra_arg1"

        private const val TAG = "FytForegroundService"
        private const val CHANNEL_ID = "fyt_custom_service_channel"
        private const val NOTIFICATION_ID = 7870
        private const val ACC_EVENT_DEBOUNCE_MS = 1_500L
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }
}
