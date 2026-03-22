package dev.igor.fytcustomservice

import android.content.Context

data class SavedMediaState(
    val packageName: String,
    val wasPlaying: Boolean
)

object MediaStateStore {
    private const val PREFS_NAME = "fyt_custom_service_media_state"
    private const val KEY_PACKAGE = "last_package"
    private const val KEY_WAS_PLAYING = "last_was_playing"

    fun saveAccOffState(context: Context, packageName: String, wasPlaying: Boolean): Boolean {
        return prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putBoolean(KEY_WAS_PLAYING, wasPlaying)
            .commit()
    }

    fun loadAccOffState(context: Context): SavedMediaState? {
        val pkg = prefs(context).getString(KEY_PACKAGE, null) ?: return null
        val wasPlaying = prefs(context).getBoolean(KEY_WAS_PLAYING, false)
        return SavedMediaState(packageName = pkg, wasPlaying = wasPlaying)
    }

    fun clearAccOffState(context: Context) {
        prefs(context).edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_WAS_PLAYING)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
