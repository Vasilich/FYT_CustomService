package dev.igor.fytcustomservice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager

object AppStorage {
    fun sharedPreferences(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }

        val deviceContext = appContext.createDeviceProtectedStorageContext()
        migrateSharedPreferencesIfNeeded(appContext, deviceContext, name)
        return deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    fun componentStartContext(context: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context
        val userManager = context.getSystemService(UserManager::class.java)
        return if (userManager != null && !userManager.isUserUnlocked) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }

    private fun migrateSharedPreferencesIfNeeded(
        appContext: Context,
        deviceContext: Context,
        name: String
    ) {
        runCatching {
            val devicePrefs = deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val appPrefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (devicePrefs.all.isEmpty() && appPrefs.all.isNotEmpty()) {
                deviceContext.moveSharedPreferencesFrom(appContext, name)
            }
        }
    }
}
