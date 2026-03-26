package dev.igor.fytcustomservice

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
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

    fun setLastStartedPlayer(context: Context, packageName: String?) {
        prefs(context).edit().putString(KEY_LAST_STARTED_PLAYER, packageName).apply()
    }

    fun getLastStartedPlayer(context: Context): String? {
        return prefs(context).getString(KEY_LAST_STARTED_PLAYER, null)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

object AccEventLog {
    private const val FILE_NAME = "FYTCustomService-acc.log"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/FYTService"

    fun append(context: Context, message: String) {
        val line = "${formatTimestamp(System.currentTimeMillis())} | $message\n"
        runCatching {
            val uri = getOrCreateLogUri(context) ?: return
            val pfd = context.contentResolver.openFileDescriptor(uri, "wa")
                ?: context.contentResolver.openFileDescriptor(uri, "w")
                ?: return
            pfd.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
        }
    }

    private fun getOrCreateLogUri(context: Context): android.net.Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FILE_NAME, "$RELATIVE_PATH/")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
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
