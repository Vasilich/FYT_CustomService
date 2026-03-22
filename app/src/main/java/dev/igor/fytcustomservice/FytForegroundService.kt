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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class FytForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAccOffHandledAtMs = 0L
    private var lastAccOnHandledAtMs = 0L
    private var pendingAccOnRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Service active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        super.onDestroy()
    }

    private fun handleAccOff() {
        if (isDuplicateAccEvent(isAccOn = false)) {
            Log.i(TAG, "ACCOFF ignored as duplicate signal")
            return
        }

        withShortWakeLock {
            val snapshot = MediaControlHelper.captureCurrentMediaSnapshot(this)
            if (snapshot == null) {
                updateStatus("ACCOFF: no active media session")
                return@withShortWakeLock
            }

            val persisted = MediaStateStore.saveAccOffState(
                context = this,
                packageName = snapshot.packageName,
                wasPlaying = snapshot.wasPlaying
            )

            MediaControlHelper.sendPause(this, snapshot.packageName)

            Log.i(
                TAG,
                "ACCOFF saved package=${snapshot.packageName}, wasPlaying=${snapshot.wasPlaying}, persisted=$persisted"
            )
            updateStatus("ACCOFF: ${snapshot.packageName} saved, PAUSE sent")
        }
    }

    private fun handleAccOn() {
        if (isDuplicateAccEvent(isAccOn = true)) {
            Log.i(TAG, "ACCON ignored as duplicate signal")
            return
        }

        withShortWakeLock {
            val saved = MediaStateStore.loadAccOffState(this)
            if (saved == null) {
                updateStatus("ACCON: nothing saved from ACCOFF")
                return@withShortWakeLock
            }

            val previousForeground = ForegroundAppHelper.getForegroundPackage(
                context = this,
                excludePackage = packageName
            )
            val launched = ForegroundAppHelper.launchPackage(this, saved.packageName)
            if (!launched) {
                updateStatus("ACCON: failed to launch ${saved.packageName}")
                Log.w(TAG, "ACCON failed to launch ${saved.packageName}")
                return@withShortWakeLock
            }

            val delayMs = ServiceSettings.accOnPlayDelayMs(this)
            updateStatus("ACCON: launched ${saved.packageName}, waiting ${delayMs}ms")

            pendingAccOnRunnable?.let(handler::removeCallbacks)
            val run = Runnable {
                withShortWakeLock {
                    if (saved.wasPlaying) {
                        MediaControlHelper.sendPlay(this, saved.packageName)
                        Log.i(TAG, "ACCON PLAY sent to ${saved.packageName}")
                    }

                    if (!previousForeground.isNullOrBlank() && previousForeground != saved.packageName) {
                        val restored = ForegroundAppHelper.launchPackage(this, previousForeground)
                        Log.i(
                            TAG,
                            "ACCON restore foreground package=$previousForeground result=$restored"
                        )
                    }

                    MediaStateStore.clearAccOffState(this)
                    updateStatus("ACCON done: ${saved.packageName}")
                    pendingAccOnRunnable = null
                }
            }
            pendingAccOnRunnable = run
            handler.postDelayed(run, delayMs.toLong())
        }
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
        val openAppIntent = Intent(this, MainActivity::class.java)
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
