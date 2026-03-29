package dev.igor.fytcustomservice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import android.view.KeyEvent

object MediaControlHelper {
    private const val TAG = "MediaControlHelper"

    fun captureCurrentMediaSnapshot(context: Context): SavedMediaState? {
        val controllers = getActiveControllers(context)
        if (controllers.isEmpty()) return null

        val active = controllers.first()
        return SavedMediaState(
            packageName = active.packageName
        )
    }

    fun sendPause(context: Context, packageName: String?) {
        sendMediaCode(context, packageName, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun sendPlay(context: Context, packageName: String?) {
        sendMediaCode(context, packageName, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun sendMediaCode(context: Context, packageName: String?, keyCode: Int) {
        val usedTransport = dispatchViaTransportControls(context, packageName, keyCode)
        if (!usedTransport) {
            dispatchViaMediaButtonIntent(context, packageName, keyCode)
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    private fun dispatchViaTransportControls(
        context: Context,
        packageName: String?,
        keyCode: Int
    ): Boolean {
        val controllers = getActiveControllers(context)
        val target = if (packageName.isNullOrBlank()) {
            controllers.firstOrNull()
        } else {
            controllers.firstOrNull { it.packageName == packageName }
        } ?: return false

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> target.transportControls.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> target.transportControls.pause()
            else -> return false
        }
        return true
    }

    private fun dispatchViaMediaButtonIntent(context: Context, packageName: String?, keyCode: Int) {
        val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            if (!packageName.isNullOrBlank()) {
                `package` = packageName
            }
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }

        val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            if (!packageName.isNullOrBlank()) {
                `package` = packageName
            }
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        context.sendOrderedBroadcast(down, null)
        context.sendOrderedBroadcast(up, null)
    }

    private fun getActiveControllers(context: Context): List<MediaController> {
        val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
        val listenerComponent = ComponentName(context, FytNotificationListenerService::class.java)

        return try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification access is required to inspect active media sessions", se)
            emptyList()
        }
    }
}
