package dev.igor.fytcustomservice

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object AccEventStateStore {
    private const val PREFS_NAME = "fyt_custom_service_acc_events"
    private const val KEY_LAST_ACC_ON_MS = "last_acc_on_ms"
    private const val KEY_LAST_ACC_OFF_MS = "last_acc_off_ms"
    private const val KEY_LAST_SAVED_PLAYER = "last_saved_player"
    private const val KEY_LAST_STARTED_PLAYER = "last_started_player"
    private const val KEY_LAST_ACTIVE_APP_BEFORE_STARTUP_TARGETS = "last_active_app_before_startup_targets"
    private const val KEY_LAST_SAVED_PLAYER_STATE = "last_saved_player_state"
    private const val KEY_LAST_STARTED_PLAYER_STATE = "last_started_player_state"

    fun setLastAccOnTimestamp(context: Context, epochMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ACC_ON_MS, epochMs).apply()
        AccEventLog.append(context, "STATE last_acc_on_ms=$epochMs")
    }

    fun setLastAccOffTimestamp(context: Context, epochMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ACC_OFF_MS, epochMs).apply()
        AccEventLog.append(context, "STATE last_acc_off_ms=$epochMs")
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
        AccEventLog.append(context, "STATE last_saved_player=${packageName ?: "[none]"}")
    }

    fun getLastSavedPlayer(context: Context): String? {
        return prefs(context).getString(KEY_LAST_SAVED_PLAYER, null)
    }

    fun setLastSavedPlayerState(context: Context, state: String?) {
        prefs(context).edit().putString(KEY_LAST_SAVED_PLAYER_STATE, state).apply()
        AccEventLog.append(context, "STATE last_saved_player_state=${state ?: "[none]"}")
    }

    fun getLastSavedPlayerState(context: Context): String? {
        return prefs(context).getString(KEY_LAST_SAVED_PLAYER_STATE, null)
    }

    fun setLastStartedPlayer(context: Context, packageName: String?) {
        prefs(context).edit().putString(KEY_LAST_STARTED_PLAYER, packageName).apply()
        AccEventLog.append(context, "STATE last_started_player=${packageName ?: "[none]"}")
    }

    fun getLastStartedPlayer(context: Context): String? {
        return prefs(context).getString(KEY_LAST_STARTED_PLAYER, null)
    }

    fun setLastActiveAppBeforeStartupTargets(context: Context, packageName: String?) {
        prefs(context).edit().putString(KEY_LAST_ACTIVE_APP_BEFORE_STARTUP_TARGETS, packageName).apply()
        AccEventLog.append(
            context,
            "STATE last_active_app_before_startup_targets=${packageName ?: "[none]"}"
        )
    }

    fun getLastActiveAppBeforeStartupTargets(context: Context): String? {
        return prefs(context).getString(KEY_LAST_ACTIVE_APP_BEFORE_STARTUP_TARGETS, null)
    }

    fun setLastStartedPlayerState(context: Context, state: String?) {
        prefs(context).edit().putString(KEY_LAST_STARTED_PLAYER_STATE, state).apply()
        AccEventLog.append(context, "STATE last_started_player_state=${state ?: "[none]"}")
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
            .remove(KEY_LAST_ACTIVE_APP_BEFORE_STARTUP_TARGETS)
            .remove(KEY_LAST_SAVED_PLAYER_STATE)
            .remove(KEY_LAST_STARTED_PLAYER_STATE)
            .apply()
        AccEventLog.append(context, "STATE cleared acc_event_store")
    }

    private fun prefs(context: Context) =
        AppStorage.sharedPreferences(context, PREFS_NAME)
}

object AccEventLog {
    private const val FILE_NAME = "FYTCustomService-acc.log"
    private const val DIR_NAME = "FYTService"
    private const val PREFS_NAME = "fyt_custom_service_acc_log"
    private const val KEY_LAST_SUCCESS_PATH = "last_success_path"
    private const val MAX_LOG_SIZE_BYTES = 100 * 1024L
    private const val TAG = "AccEventLog"

    @Synchronized
    fun append(context: Context, message: String) {
        val line = "${formatTimestamp(System.currentTimeMillis())} | $message\n"
        val file = publicDocumentsLogFile()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendToMediaStore(context.applicationContext, line)
            } else {
                appendToFile(file, line)
            }
            cacheLastSuccessPath(context, file.absolutePath)
        }.onFailure { err ->
            Log.e(TAG, "Failed to append log to ${file.absolutePath}", err)
        }
    }

    fun logPathForUi(context: Context): String {
        val cached = AppStorage.sharedPreferences(context, PREFS_NAME)
            .getString(KEY_LAST_SUCCESS_PATH, null)
        if (!cached.isNullOrBlank()) return cached
        return expectedPublicPath()
    }

    private fun expectedPublicPath(): String {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(File(docs, DIR_NAME), FILE_NAME).absolutePath
    }

    private fun publicDocumentsLogFile(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(File(docs, DIR_NAME), FILE_NAME)
    }

    private fun appendToMediaStore(context: Context, line: String) {
        val resolver = context.contentResolver
        val incoming = line.toByteArray(Charsets.UTF_8)
        var uri = findMediaStoreLogUri(resolver)
        val currentSize = uri?.let { mediaStoreFileSize(resolver, it) } ?: 0L
        if (uri != null && currentSize + incoming.size > MAX_LOG_SIZE_BYTES) {
            rotateMediaStoreLog(resolver, uri)
            uri = null
        }
        val targetUri = uri ?: createMediaStoreLogUri(resolver)
        resolver.openOutputStream(targetUri, "wa")?.use { out ->
            out.write(incoming)
        } ?: error("Failed to open MediaStore log output stream")
    }

    private fun findMediaStoreLogUri(resolver: android.content.ContentResolver): Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$FILE_NAME%", "%${Environment.DIRECTORY_DOCUMENTS}/$DIR_NAME/%")
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        var newestUri: Uri? = null
        resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val uri = ContentUris.withAppendedId(collection, id)
                if (name == FILE_NAME) {
                    return uri
                }
                if (newestUri == null) {
                    newestUri = uri
                }
            }
        }
        return newestUri
    }

    private fun createMediaStoreLogUri(resolver: android.content.ContentResolver): Uri {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath())
        }
        return resolver.insert(collection, values)
            ?: error("Failed to create MediaStore log file")
    }

    private fun mediaStoreFileSize(resolver: android.content.ContentResolver, uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            }
        }
        return 0L
    }

    private fun rotateMediaStoreLog(resolver: android.content.ContentResolver, uri: Uri) {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(
            Instant.now().atZone(ZoneId.systemDefault())
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FYTCustomService-acc-$stamp.log")
        }
        resolver.update(uri, values, null, null)
    }

    private fun mediaStoreRelativePath(): String {
        return "${Environment.DIRECTORY_DOCUMENTS}/$DIR_NAME/"
    }

    private fun appendToFile(file: File, line: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                error("Failed to create directory: ${parent.absolutePath}")
            }
        }

        rotateIfNeeded(file, line.toByteArray(Charsets.UTF_8).size)
        file.appendText(line, Charsets.UTF_8)
    }

    private fun rotateIfNeeded(source: File, incomingSize: Int) {
        if (!source.exists()) return
        if (source.length() + incomingSize <= MAX_LOG_SIZE_BYTES) return

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(
            Instant.now().atZone(ZoneId.systemDefault())
        )
        val archive = File(source.parentFile, "FYTCustomService-acc-$stamp.log")
        source.copyTo(archive, overwrite = true)
        source.writeText("", Charsets.UTF_8)
    }

    private fun cacheLastSuccessPath(context: Context, absolutePath: String) {
        AppStorage.sharedPreferences(context, PREFS_NAME)
            .edit()
            .putString(KEY_LAST_SUCCESS_PATH, absolutePath)
            .apply()
    }
}

object AccEventTimeFormatter {
    fun formatForUi(epochMs: Long?): String {
        if (epochMs == null) return "never"
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
}

private fun formatTimestamp(epochMs: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
}
