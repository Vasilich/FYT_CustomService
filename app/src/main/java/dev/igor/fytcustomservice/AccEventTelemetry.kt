package dev.igor.fytcustomservice

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AccEventStateStore {
    private const val PREFS_NAME = "fyt_custom_service_acc_events"
    private const val KEY_LAST_ACC_ON_MS = "last_acc_on_ms"
    private const val KEY_LAST_ACC_OFF_MS = "last_acc_off_ms"
    private const val KEY_LAST_SAVED_PLAYER = "last_saved_player"
    private const val KEY_LAST_STARTED_PLAYER = "last_started_player"
    private const val KEY_LAST_SAVED_PLAYER_STATE = "last_saved_player_state"
    private const val KEY_LAST_STARTED_PLAYER_STATE = "last_started_player_state"

    fun setLastAccOnTimestamp(context: Context, epochMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ACC_ON_MS, epochMs).apply()
    }

    fun setLastAccOffTimestamp(context: Context, epochMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ACC_OFF_MS, epochMs).apply()
    }

    fun getLastAccOnTimestamp(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_ACC_ON_MS, 0L)
        return v.takeIf { it > 0L }
    }

    fun getLastAccOffTimestamp(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_ACC_OFF_MS, 0L)
        return v.takeIf { it > 0L }
    }

    fun setLastSavedPlayer(context: Context, packageName: String?) {
        prefs(context).edit().putString(KEY_LAST_SAVED_PLAYER, packageName).apply()
    }

    fun getLastSavedPlayer(context: Context): String? {
        return prefs(context).getString(KEY_LAST_SAVED_PLAYER, null)
    }

    fun setLastSavedPlayerState(context: Context, state: String?) {
        prefs(context).edit().putString(KEY_LAST_SAVED_PLAYER_STATE, state).apply()
    }

    fun getLastSavedPlayerState(context: Context): String? {
        return prefs(context).getString(KEY_LAST_SAVED_PLAYER_STATE, null)
    }

    fun setLastStartedPlayer(context: Context, packageName: String?) {
        prefs(context).edit().putString(KEY_LAST_STARTED_PLAYER, packageName).apply()
    }

    fun getLastStartedPlayer(context: Context): String? {
        return prefs(context).getString(KEY_LAST_STARTED_PLAYER, null)
    }

    fun setLastStartedPlayerState(context: Context, state: String?) {
        prefs(context).edit().putString(KEY_LAST_STARTED_PLAYER_STATE, state).apply()
    }

    fun getLastStartedPlayerState(context: Context): String? {
        return prefs(context).getString(KEY_LAST_STARTED_PLAYER_STATE, null)
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_ACC_ON_MS)
            .remove(KEY_LAST_ACC_OFF_MS)
            .remove(KEY_LAST_SAVED_PLAYER)
            .remove(KEY_LAST_STARTED_PLAYER)
            .remove(KEY_LAST_SAVED_PLAYER_STATE)
            .remove(KEY_LAST_STARTED_PLAYER_STATE)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

object AccEventLog {
    private const val FILE_NAME = "FYTCustomService-acc.log"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/FYTService/"
    private const val PREFS_NAME = "fyt_custom_service_acc_log"
    private const val KEY_ACTIVE_LOG_URI = "active_log_uri"
    private const val MAX_LOG_SIZE_BYTES = 100 * 1024L
    private const val TAG = "AccEventLog"

    fun append(context: Context, message: String) {
        val line = "${formatTimestamp(System.currentTimeMillis())} | $message\n"
        runCatching {
            val uri = getOrCreateLogUri(context) ?: return
            rotateIfNeeded(context, uri, line.toByteArray(Charsets.UTF_8).size)
            val pfd = context.contentResolver.openFileDescriptor(uri, "wa")
                ?: context.contentResolver.openFileDescriptor(uri, "w")
                ?: return
            pfd.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to append log", err)
        }
    }

    private fun rotateIfNeeded(context: Context, sourceUri: android.net.Uri, incomingSize: Int) {
        val sourcePfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return
        val currentSize = sourcePfd.use { it.statSize }
        if (currentSize < 0 || currentSize + incomingSize <= MAX_LOG_SIZE_BYTES) return

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(
            Instant.now().atZone(ZoneId.systemDefault())
        )
        val archiveName = "FYTCustomService-acc-$stamp.log"
        val archiveUri = createLogUri(context, archiveName) ?: return

        val input = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return
        val output = context.contentResolver.openFileDescriptor(archiveUri, "w") ?: return
        input.use { src ->
            output.use { dst ->
                FileInputStream(src.fileDescriptor).use { fis ->
                    FileOutputStream(dst.fileDescriptor).use { fos ->
                        fis.copyTo(fos)
                        fos.flush()
                    }
                }
            }
        }

        val truncatePfd = context.contentResolver.openFileDescriptor(sourceUri, "rw") ?: return
        truncatePfd.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { ch ->
                ch.truncate(0L)
            }
        }
    }

    private fun getOrCreateLogUri(context: Context): android.net.Uri? {
        val resolver = context.contentResolver
        val cachedUriText = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_LOG_URI, null)
        if (!cachedUriText.isNullOrBlank()) {
            val cachedUri = android.net.Uri.parse(cachedUriText)
            val pfd = resolver.openFileDescriptor(cachedUri, "r")
            if (pfd != null) {
                pfd.close()
                return cachedUri
            }
        }

        val found = findExistingLogUri(context, FILE_NAME)
        if (found != null) {
            cacheLogUri(context, found)
            return found
        }

        val created = createLogUri(context, FILE_NAME)
        if (created != null) {
            cacheLogUri(context, created)
        }
        return created
    }

    private fun findExistingLogUri(context: Context, fileName: String): android.net.Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection =
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(fileName, RELATIVE_PATH)
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            }
        }
        return null
    }

    private fun createLogUri(context: Context, fileName: String): android.net.Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Files.FileColumns.IS_PENDING, 0)
        }
        val fileUri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
        if (fileUri != null) return fileUri

        val dlValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
    }

    private fun cacheLogUri(context: Context, uri: android.net.Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_LOG_URI, uri.toString())
            .apply()
    }
}

object AccEventTimeFormatter {
    fun formatForUi(epochMs: Long?): String {
        if (epochMs == null) return "never"
        return formatTimestamp(epochMs)
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    )
}
