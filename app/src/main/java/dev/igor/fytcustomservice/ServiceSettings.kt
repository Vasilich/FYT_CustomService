package dev.igor.fytcustomservice

import android.content.Context

object ServiceSettings {
    private const val PREFS_NAME = "fyt_custom_service_prefs"
    private const val KEY_COMMAND_ACTION = "command_action"
    private const val KEY_AUTOSTART = "autostart_on_boot"
    private const val KEY_ACC_ON_PLAY_DELAY_MS = "acc_on_play_delay_ms"
    private const val KEY_ACC_ON_FALLBACK_PLAYER_PACKAGE = "acc_on_fallback_player_package"

    const val DEFAULT_COMMAND_ACTION = "dev.igor.fytcustomservice.ACTION_COMMAND"
    const val DEFAULT_ACC_ON_PLAY_DELAY_MS = 2_000
    private const val MIN_ACC_ON_PLAY_DELAY_MS = 500
    private const val MAX_ACC_ON_PLAY_DELAY_MS = 10_000

    fun commandAction(context: Context): String {
        return prefs(context).getString(KEY_COMMAND_ACTION, DEFAULT_COMMAND_ACTION)
            ?: DEFAULT_COMMAND_ACTION
    }

    fun autoStartOnBoot(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTOSTART, true)
    }

    fun accOnPlayDelayMs(context: Context): Int {
        val saved = prefs(context).getInt(KEY_ACC_ON_PLAY_DELAY_MS, DEFAULT_ACC_ON_PLAY_DELAY_MS)
        return saved.coerceIn(MIN_ACC_ON_PLAY_DELAY_MS, MAX_ACC_ON_PLAY_DELAY_MS)
    }

    fun accOnFallbackPlayerPackage(context: Context): String? {
        return prefs(context).getString(KEY_ACC_ON_FALLBACK_PLAYER_PACKAGE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun parseAccOnPlayDelayMs(rawValue: String): Int {
        val parsed = rawValue.trim().toIntOrNull() ?: DEFAULT_ACC_ON_PLAY_DELAY_MS
        return parsed.coerceIn(MIN_ACC_ON_PLAY_DELAY_MS, MAX_ACC_ON_PLAY_DELAY_MS)
    }

    fun saveSettings(context: Context, commandAction: String, autoStartOnBoot: Boolean) {
        saveSettings(
            context = context,
            commandAction = commandAction,
            autoStartOnBoot = autoStartOnBoot,
            accOnPlayDelayMs = accOnPlayDelayMs(context),
            accOnFallbackPlayerPackage = accOnFallbackPlayerPackage(context)
        )
    }

    fun saveSettings(
        context: Context,
        commandAction: String,
        autoStartOnBoot: Boolean,
        accOnPlayDelayMs: Int,
        accOnFallbackPlayerPackage: String?
    ) {
        prefs(context).edit()
            .putString(KEY_COMMAND_ACTION, commandAction)
            .putBoolean(KEY_AUTOSTART, autoStartOnBoot)
            .putInt(
                KEY_ACC_ON_PLAY_DELAY_MS,
                accOnPlayDelayMs.coerceIn(MIN_ACC_ON_PLAY_DELAY_MS, MAX_ACC_ON_PLAY_DELAY_MS)
            )
            .putString(
                KEY_ACC_ON_FALLBACK_PLAYER_PACKAGE,
                accOnFallbackPlayerPackage?.trim()?.takeIf { it.isNotBlank() }
            )
            .apply()
    }

    private fun prefs(context: Context) =
        AppStorage.sharedPreferences(context, PREFS_NAME)
}
