package dev.igor.fytcustomservice

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnTestCommand = findViewById<Button>(R.id.btnTestCommand)

        btnStart.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FytForegroundService::class.java).setAction(FytForegroundService.ACTION_START)
            )
            statusText.text = getString(R.string.status_running)
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, FytForegroundService::class.java).setAction(FytForegroundService.ACTION_STOP)
            )
            statusText.text = getString(R.string.status_stopped)
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        btnTestCommand.setOnClickListener {
            val commandIntent = Intent(ServiceSettings.commandAction(this)).apply {
                setPackage(packageName)
                putExtra(FytForegroundService.EXTRA_COMMAND_CODE, 1)
                putExtra(FytForegroundService.EXTRA_ARG1, 77)
            }
            sendBroadcast(commandIntent)
        }

        requestNotificationPermissionIfNeeded()
        promptRequiredAccessesIfNeeded()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val actionEdit = view.findViewById<EditText>(R.id.editAction)
        val autoStartSwitch = view.findViewById<Switch>(R.id.switchAutostart)
        val accOnDelayEdit = view.findViewById<EditText>(R.id.editAccOnDelayMs)

        actionEdit.setText(ServiceSettings.commandAction(this))
        autoStartSwitch.isChecked = ServiceSettings.autoStartOnBoot(this)
        accOnDelayEdit.setText(ServiceSettings.accOnPlayDelayMs(this).toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val delayMs = ServiceSettings.parseAccOnPlayDelayMs(
                    accOnDelayEdit.text?.toString().orEmpty()
                )
                ServiceSettings.saveSettings(
                    context = this,
                    commandAction = actionEdit.text.toString().trim().ifBlank {
                        ServiceSettings.DEFAULT_COMMAND_ACTION
                    },
                    autoStartOnBoot = autoStartSwitch.isChecked,
                    accOnPlayDelayMs = delayMs
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun promptRequiredAccessesIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasNotificationListenerAccess()) {
            missing += getString(R.string.missing_notification_access)
        }
        if (!hasUsageAccess()) {
            missing += getString(R.string.missing_usage_access)
        }

        if (missing.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.required_access_title)
            .setMessage(missing.joinToString(separator = "\n"))
            .setPositiveButton(R.string.open_setup) { _, _ ->
                openRequiredSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openRequiredSettings() {
        if (!hasNotificationListenerAccess()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        if (!hasUsageAccess()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
