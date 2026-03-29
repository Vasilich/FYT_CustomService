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
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logPathText: TextView
    private lateinit var lastAccOnText: TextView
    private lateinit var lastAccOffText: TextView
    private lateinit var lastSavedPlayerText: TextView
    private lateinit var lastStartedPlayerText: TextView
    private lateinit var lastActiveAppBeforeTargetsText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WatchdogScheduler.ensureScheduled(this)

        statusText = findViewById(R.id.statusText)
        logPathText = findViewById(R.id.logPathText)
        lastAccOnText = findViewById(R.id.lastAccOnText)
        lastAccOffText = findViewById(R.id.lastAccOffText)
        lastSavedPlayerText = findViewById(R.id.lastSavedPlayerText)
        lastStartedPlayerText = findViewById(R.id.lastStartedPlayerText)
        lastActiveAppBeforeTargetsText = findViewById(R.id.lastActiveAppBeforeTargetsText)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnAccOnTargets = findViewById<Button>(R.id.btnAccOnTargets)
        val btnTestCommand = findViewById<Button>(R.id.btnTestCommand)
        val btnEmulateAccOn = findViewById<Button>(R.id.btnEmulateAccOn)
        val btnEmulateAccOff = findViewById<Button>(R.id.btnEmulateAccOff)
        val btnResetAccState = findViewById<Button>(R.id.btnResetAccState)

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
                showAccOnTargetsEditorDialog()
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

        btnEmulateAccOn.setOnClickListener {
            emulateAccBroadcast(AccPowerReceiver.ACTION_ACC_ON)
        }
        btnEmulateAccOff.setOnClickListener {
            emulateAccBroadcast(AccPowerReceiver.ACTION_ACC_OFF)
        }
        btnResetAccState.setOnClickListener {
            startService(
                Intent(this, FytForegroundService::class.java).setAction(FytForegroundService.ACTION_RESET_STATE)
            )
            refreshAccEventTimestamps()
            scheduleAccUiRefresh()
        }

        requestNotificationPermissionIfNeeded()
        promptRequiredAccessesIfNeeded()
        refreshServiceStatusText()
        refreshAccEventTimestamps()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatusText()
        refreshAccEventTimestamps()
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

    private fun showAccOnTargetsEditorDialog() {
        val workingTargets = AccOnStartupStore.load(this).toMutableList()
        val view = layoutInflater.inflate(R.layout.dialog_acc_on_targets_editor, null)
        val listView = view.findViewById<ListView>(R.id.listTargets)
        val btnAdd = view.findViewById<Button>(R.id.btnAddTarget)
        val btnEdit = view.findViewById<Button>(R.id.btnEditTarget)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteTarget)
        val btnMoveUp = view.findViewById<Button>(R.id.btnMoveUpTarget)
        val btnMoveDown = view.findViewById<Button>(R.id.btnMoveDownTarget)

        val adapter = TargetManageAdapter(this, workingTargets)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        var selectedIndex = if (workingTargets.isNotEmpty()) 0 else -1

        fun selectIndex(index: Int) {
            selectedIndex = if (workingTargets.isEmpty()) {
                -1
            } else {
                index.coerceIn(0, workingTargets.lastIndex)
            }
            adapter.setSelectedIndex(selectedIndex)
            listView.clearChoices()
            if (selectedIndex >= 0) {
                listView.setItemChecked(selectedIndex, true)
                listView.setSelection(selectedIndex)
            }
        }

        fun refreshButtons() {
            val hasSelection = selectedIndex in workingTargets.indices
            btnEdit.isEnabled = hasSelection
            btnDelete.isEnabled = hasSelection
            btnMoveUp.isEnabled = hasSelection && selectedIndex > 0
            btnMoveDown.isEnabled = hasSelection && selectedIndex < workingTargets.lastIndex
        }

        fun refreshUi() {
            adapter.notifyDataSetChanged()
            if (workingTargets.isNotEmpty() && selectedIndex < 0) {
                selectedIndex = 0
            }
            selectIndex(selectedIndex)
            refreshButtons()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            refreshButtons()
        }

        btnAdd.setOnClickListener {
            runTargetWizard(existing = null) { created ->
                workingTargets.add(created)
                selectedIndex = workingTargets.lastIndex
                refreshUi()
            }
        }

        btnEdit.setOnClickListener {
            val index = selectedIndex
            if (index !in workingTargets.indices) return@setOnClickListener
            runTargetWizard(existing = workingTargets[index]) { updated ->
                workingTargets[index] = updated
                selectedIndex = index
                refreshUi()
            }
        }

        btnDelete.setOnClickListener {
            val index = selectedIndex
            if (index !in workingTargets.indices) return@setOnClickListener
            val target = workingTargets[index]
            AlertDialog.Builder(this)
                .setTitle("Delete target?")
                .setMessage(
                    "${target.packageName}\n" +
                        "${target.activityName ?: "[default launcher activity]"}"
                )
                .setPositiveButton("Delete") { _, _ ->
                    workingTargets.removeAt(index)
                    selectedIndex = if (workingTargets.isEmpty()) -1 else (index - 1).coerceAtLeast(0)
                    refreshUi()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        btnMoveUp.setOnClickListener {
            val index = selectedIndex
            if (index !in workingTargets.indices || index == 0) return@setOnClickListener
            val moved = workingTargets.removeAt(index)
            workingTargets.add(index - 1, moved)
            selectedIndex = index - 1
            refreshUi()
        }

        btnMoveDown.setOnClickListener {
            val index = selectedIndex
            if (index !in workingTargets.indices || index >= workingTargets.lastIndex) {
                return@setOnClickListener
            }
            val moved = workingTargets.removeAt(index)
            workingTargets.add(index + 1, moved)
            selectedIndex = index + 1
            refreshUi()
        }

        refreshUi()

        val dialog = AlertDialog.Builder(this)
            .setTitle("ACC ON startup targets")
            .setView(view)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                AccOnStartupStore.save(this, workingTargets)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun runTargetWizard(existing: AccOnStartupTarget?, onComplete: (AccOnStartupTarget) -> Unit) {
        if (existing != null) {
            showActivityAndDelayDialog(
                selectedPackage = existing.packageName,
                currentPauseMs = existing.pauseAfterMs,
                currentActivityName = existing.activityName
            ) { activityName, pauseMs ->
                onComplete(
                    existing.copy(
                        activityName = activityName,
                        pauseAfterMs = pauseMs
                    )
                )
            }
            return
        }

        showAppPickerForTarget(null) { packageName ->
            showActivityAndDelayDialog(
                selectedPackage = packageName,
                currentPauseMs = 1500,
                currentActivityName = null
            ) { activityName, pauseMs ->
                onComplete(
                    AccOnStartupTarget(
                        packageName = packageName,
                        activityName = activityName,
                        pauseAfterMs = pauseMs
                    )
                )
            }
        }
    }

    private fun showAppPickerForTarget(
        preferredPackage: String?,
        onPackageSelected: (String) -> Unit
    ) {
        val apps = InstalledAppCatalog.queryLaunchableApps(this)
            .filter { it.packageName != packageName }
        if (apps.isEmpty()) {
            toast("No launchable apps found")
            return
        }

        var checkedIndex = apps.indexOfFirst { it.packageName == preferredPackage }
        if (checkedIndex < 0) checkedIndex = 0

        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            dividerHeight = 4
        }
        val adapter = AppPickerAdapter(this, apps)
        listView.adapter = adapter
        listView.setItemChecked(checkedIndex, true)
        listView.setSelection(checkedIndex)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select app")
            .setView(listView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            onPackageSelected(apps[position].packageName)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showActivityAndDelayDialog(
        selectedPackage: String,
        currentPauseMs: Int,
        currentActivityName: String?,
        onComplete: (String?, Int) -> Unit
    ) {
        val activities = InstalledAppCatalog.queryExportedActivities(this, selectedPackage)
        val options = buildList {
            add("Default launcher activity")
            addAll(activities.map { "${it.displayName}\n${it.activityName}" })
        }

        val selectedIndex = currentActivityName?.let { existing ->
            activities.indexOfFirst { it.activityName == existing }
                .takeIf { it >= 0 }
                ?.plus(1)
        } ?: 0

        val view = layoutInflater.inflate(R.layout.dialog_target_activity_delay, null)
        val appIcon = view.findViewById<ImageView>(R.id.iconTargetApp)
        val appNameText = view.findViewById<TextView>(R.id.textTargetAppName)
        val pkgText = view.findViewById<TextView>(R.id.textTargetPackage)
        val spinner = view.findViewById<Spinner>(R.id.spinnerActivity)
        val delayEdit = view.findViewById<EditText>(R.id.editDelayMs)

        val appName = runCatching {
            val appInfo = packageManager.getApplicationInfo(selectedPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(selectedPackage)
        val icon = runCatching {
            packageManager.getApplicationIcon(selectedPackage)
        }.getOrDefault(
            appIcon.context.getDrawable(android.R.drawable.sym_def_app_icon)
        )

        appIcon.setImageDrawable(icon)
        appNameText.text = appName
        pkgText.text = selectedPackage
        spinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
        spinner.setSelection(selectedIndex, false)

        delayEdit.setText(currentPauseMs.toString())

        AlertDialog.Builder(this)
            .setTitle("Activity and delay")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                val index = spinner.selectedItemPosition.coerceAtLeast(0)
                val activityName = if (index == 0) null else activities[index - 1].activityName
                val pauseMs = AccOnStartupStore.parsePauseMs(delayEdit.text?.toString().orEmpty())
                onComplete(activityName, pauseMs)
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

    private fun emulateAccBroadcast(action: String) {
        val intent = Intent(this, AccPowerReceiver::class.java).apply {
            this.action = action
        }
        sendBroadcast(intent)
        toast("Sent $action")
        scheduleAccUiRefresh()
    }

    private fun scheduleAccUiRefresh() {
        window.decorView.postDelayed({ refreshAccEventTimestamps() }, 400L)
    }

    private fun refreshServiceStatusText() {
        statusText.text = if (ServiceRuntimeHelper.isForegroundServiceRunning(this)) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
        logPathText.text = getString(R.string.log_file_path_format, AccEventLog.logPathForUi(this))
    }

    private fun refreshAccEventTimestamps() {
        val lastOn = AccEventTimeFormatter.formatForUi(AccEventStateStore.getLastAccOnTimestamp(this))
        val lastOff = AccEventTimeFormatter.formatForUi(AccEventStateStore.getLastAccOffTimestamp(this))
        val lastSavedPlayer = buildPlayerLabel(AccEventStateStore.getLastSavedPlayer(this))
        val lastStartedPlayer = buildPlayerLabel(AccEventStateStore.getLastStartedPlayer(this))
        val lastActiveBeforeTargets = buildPlayerLabel(
            AccEventStateStore.getLastActiveAppBeforeStartupTargets(this)
        )
        lastAccOnText.text = getString(R.string.last_acc_on_format, lastOn)
        lastAccOffText.text = getString(R.string.last_acc_off_format, lastOff)
        lastSavedPlayerText.text = getString(R.string.last_saved_player_format, lastSavedPlayer)
        lastStartedPlayerText.text = getString(R.string.last_started_player_format, lastStartedPlayer)
        lastActiveAppBeforeTargetsText.text = getString(
            R.string.last_active_app_before_targets_format,
            lastActiveBeforeTargets
        )
    }

    private fun buildPlayerLabel(playerPackage: String?): String {
        return playerPackage.orEmpty().ifBlank { "-" }
    }

    private class TargetManageAdapter(
        activity: MainActivity,
        targets: List<AccOnStartupTarget>
    ) : ArrayAdapter<AccOnStartupTarget>(activity, 0, targets) {
        private val inflater = LayoutInflater.from(activity)
        private val pm = activity.packageManager
        private var selectedIndex: Int = -1

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
            val selected = position == selectedIndex
            view.isActivated = selected
            view.isSelected = selected

            return view
        }

        fun setSelectedIndex(index: Int) {
            selectedIndex = index
            notifyDataSetChanged()
        }

        private fun defaultIcon(iconView: ImageView): Drawable {
            return iconView.context.getDrawable(android.R.drawable.sym_def_app_icon)
                ?: throw IllegalStateException("Default app icon missing")
        }
    }

    private class AppPickerAdapter(
        activity: MainActivity,
        apps: List<InstalledAppEntry>
    ) : ArrayAdapter<InstalledAppEntry>(activity, 0, apps) {
        private val inflater = LayoutInflater.from(activity)
        private val pm = activity.packageManager

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_picker, parent, false)
            val app = getItem(position) ?: return view

            val iconView = view.findViewById<ImageView>(R.id.iconApp)
            val titleView = view.findViewById<TextView>(R.id.textAppTitle)
            val subtitleView = view.findViewById<TextView>(R.id.textAppSubtitle)

            val icon = runCatching {
                pm.getApplicationIcon(app.packageName)
            }.getOrDefault(
                iconView.context.getDrawable(android.R.drawable.sym_def_app_icon)
            )
            iconView.setImageDrawable(icon)
            titleView.text = app.displayName
            subtitleView.text = app.packageName
            return view
        }
    }
}
