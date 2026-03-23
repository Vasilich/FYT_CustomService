package dev.igor.fytcustomservice

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
        WatchdogScheduler.ensureScheduled(this)

        val statusText = findViewById<TextView>(R.id.statusText)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnAccOnTargets = findViewById<Button>(R.id.btnAccOnTargets)
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
        btnAccOnTargets.setOnClickListener {
            if (ensureRequiredAccessesForConfiguration()) {
                showAccOnTargetsDialog()
            }
        }

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

    private fun showAccOnTargetsDialog() {
        val targets = AccOnStartupStore.load(this)
        val body = if (targets.isEmpty()) {
            "No startup targets configured."
        } else {
            targets.mapIndexed { idx, target ->
                "${idx + 1}. ${target.packageName}\n   " +
                    "${target.activityName ?: "[default launcher activity]"} (${target.pauseAfterMs}ms)"
            }.joinToString(separator = "\n\n")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("ACC ON startup targets")
            .setMessage(body)
            .setPositiveButton("Add target") { _, _ ->
                showAppPickerForTarget()
            }
            .setNegativeButton("Close", null)

        if (targets.isNotEmpty()) {
            builder.setNeutralButton("Manage targets") { _, _ ->
                showManageTargetsDialog(targets)
            }
        }

        builder.show()
    }

    private fun showManageTargetsDialog(targets: List<AccOnStartupTarget>) {
        val adapter = TargetManageAdapter(this, targets)
        AlertDialog.Builder(this)
            .setTitle("Manage startup targets")
            .setAdapter(adapter) { _, which ->
                val target = targets[which]
                showTargetActionsDialog(targets = targets, index = which, target = target)
            }
            .setPositiveButton("Add target") { _, _ -> showAppPickerForTarget() }
            .setNeutralButton("Clear all targets") { _, _ ->
                AccOnStartupStore.save(this, emptyList())
                showAccOnTargetsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTargetActionsDialog(
        targets: List<AccOnStartupTarget>,
        index: Int,
        target: AccOnStartupTarget
    ) {
        val options = buildList {
            if (index > 0) add("Move up")
            if (index < targets.lastIndex) add("Move down")
            add("Remove")
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Target actions\n${target.packageName}\n" +
                    "${target.activityName ?: "[default launcher activity]"}"
            )
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Move up" -> {
                        val updated = targets.toMutableList().apply {
                            val moved = removeAt(index)
                            add(index - 1, moved)
                        }
                        AccOnStartupStore.save(this, updated)
                        showManageTargetsDialog(updated)
                    }

                    "Move down" -> {
                        val updated = targets.toMutableList().apply {
                            val moved = removeAt(index)
                            add(index + 1, moved)
                        }
                        AccOnStartupStore.save(this, updated)
                        showManageTargetsDialog(updated)
                    }

                    "Remove" -> {
                        val updated = targets.toMutableList().apply { removeAt(index) }
                        AccOnStartupStore.save(this, updated)
                        showAccOnTargetsDialog()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppPickerForTarget() {
        val apps = InstalledAppCatalog.queryLaunchableApps(this)
            .filter { it.packageName != packageName }
        if (apps.isEmpty()) {
            toast("No launchable apps found")
            return
        }

        val labels = apps.map { "${it.displayName} (${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select app")
            .setItems(labels) { _, which ->
                showActivityPickerForTarget(apps[which].packageName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showActivityPickerForTarget(selectedPackage: String) {
        val activities = InstalledAppCatalog.queryExportedActivities(this, selectedPackage)
        val labels = buildList {
            add("Default launcher activity")
            addAll(activities.map { "${it.displayName}\n${it.activityName}" })
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select activity")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    showPauseDialogForTarget(
                        packageName = selectedPackage,
                        activityName = null
                    )
                } else {
                    val selected = activities[which - 1]
                    showPauseDialogForTarget(
                        packageName = selected.packageName,
                        activityName = selected.activityName
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPauseDialogForTarget(packageName: String, activityName: String?) {
        val pauseInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1500")
            hint = "Pause after start (ms)"
        }

        AlertDialog.Builder(this)
            .setTitle("Pause after start (ms)")
            .setView(pauseInput)
            .setPositiveButton(R.string.save) { _, _ ->
                val pauseMs = AccOnStartupStore.parsePauseMs(pauseInput.text?.toString().orEmpty())
                val targets = AccOnStartupStore.load(this).toMutableList()
                val existingIndex = targets.indexOfFirst {
                    it.packageName == packageName &&
                        normalizeActivityName(it.activityName) == normalizeActivityName(activityName)
                }
                val target = AccOnStartupTarget(
                    packageName = packageName,
                    activityName = activityName,
                    pauseAfterMs = pauseMs
                )
                if (existingIndex >= 0) {
                    targets[existingIndex] = target
                } else {
                    targets += target
                }
                AccOnStartupStore.save(this, targets)
                showAccOnTargetsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ensureRequiredAccessesForConfiguration(): Boolean {
        requestNotificationPermissionIfNeeded()
        val ok = hasNotificationListenerAccess() && hasUsageAccess()
        if (!ok) {
            promptRequiredAccessesIfNeeded()
        }
        return ok
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun normalizeActivityName(activityName: String?): String {
        return activityName?.takeIf { it.isNotBlank() } ?: ""
    }

    private class TargetManageAdapter(
        activity: MainActivity,
        targets: List<AccOnStartupTarget>
    ) : ArrayAdapter<AccOnStartupTarget>(activity, 0, targets) {
        private val inflater = LayoutInflater.from(activity)
        private val pm = activity.packageManager

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_manage_target, parent, false)
            val target = getItem(position) ?: return view

            val iconView = view.findViewById<ImageView>(R.id.iconApp)
            val titleView = view.findViewById<TextView>(R.id.textAppTitle)
            val subtitleView = view.findViewById<TextView>(R.id.textAppSubtitle)

            val appLabel = runCatching {
                val appInfo = pm.getApplicationInfo(target.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(target.packageName)

            val icon = runCatching {
                pm.getApplicationIcon(target.packageName)
            }.getOrDefault(defaultIcon(iconView))

            iconView.setImageDrawable(icon)
            titleView.text = appLabel
            subtitleView.text =
                "${target.activityName ?: "[default launcher activity]"} (${target.pauseAfterMs}ms)"

            return view
        }

        private fun defaultIcon(iconView: ImageView): Drawable {
            return iconView.context.getDrawable(android.R.drawable.sym_def_app_icon)
                ?: throw IllegalStateException("Default app icon missing")
        }
    }
}
