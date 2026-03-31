package dev.igor.fytcustomservice

import android.content.Context

data class SavedMediaState(
    val packageName: String,
    val playerState: String = "unknown"
)

object MediaStateStore {
    private const val PREFS_NAME = "fyt_custom_service_media_state"
    private const val KEY_PACKAGE = "last_package"

    fun saveAccOffState(context: Context, packageName: String): Boolean {
        return prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .commit()
    }

    fun loadAccOffState(context: Context): SavedMediaState? {
        val pkg = prefs(context).getString(KEY_PACKAGE, null) ?: return null
        return SavedMediaState(packageName = pkg)
    }

    fun clearAccOffState(context: Context) {
        prefs(context).edit()
            .remove(KEY_PACKAGE)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
