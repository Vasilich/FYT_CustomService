# FYT custom service

`FYT custom service` is an Android app designed for FYT-based Android 13 head units (UIS7870 class devices). It runs a foreground service with sticky restart behavior and can execute custom code paths based on numeric parameters received through intents.

## What it does
- Starts a persistent foreground service (`START_STICKY` + ongoing notification).
- Uses a headless launcher activity for FYT autostart triggers (no settings UI shown if service is already running).
- Keeps manual settings access via a separate launcher entry: `FYT custom service settings`.
- Listens for custom broadcast intents with numeric parameters.
- Dispatches logic by command code in `FytForegroundService.executeCommand(...)`.
- Handles FYT ACC events:
  - `com.fyt.boot.ACCOFF`: save current media app package + playing state, then send `PAUSE`.
  - `com.fyt.boot.ACCON`: launch saved player, wait configurable delay (default 2 seconds), send `PLAY`, then restore the app that was foreground before starting the player.
- Supports ACCON startup target list (persisted on disk):
  - Configure ordered app/activity starts.
  - Per-target pause after launch.
  - Optional default launcher activity (no explicit activity required).
  - Skip target if package is already running.
- Provides a minimal GUI to:
  - Start/stop the service.
  - Show service status (`running`/`stopped`) and last received ACCON/ACCOFF timestamps.
  - Change command action string.
  - Toggle auto-start on boot.
  - Set ACCON play delay in milliseconds (500-10000 ms).
  - Configure ACCON startup targets.
- Schedules a watchdog worker (15-minute periodic check) to restart the service if killed.
- Writes ACC event logs to public Documents folder (`Documents/FYTService/FYTCustomService-acc.log`).

## Intent contract
Default action:
- `dev.igor.fytcustomservice.ACTION_COMMAND`

Extras:
- `extra_command_code` (`Int`) - required command selector.
- `extra_arg1` (`Int`) - optional additional numeric argument.

Example ADB command:

```bash
adb shell am broadcast \
  -a dev.igor.fytcustomservice.ACTION_COMMAND \
  --ei extra_command_code 1 \
  --ei extra_arg1 123
```

## ACC behavior details
Input broadcasts expected from FYT system:
- `com.fyt.boot.ACCON`
- `com.fyt.boot.ACCOFF`

ACCOFF flow:
1. Read current active media session.
2. Persist `packageName` + `wasPlaying` using synchronous commit.
3. Send media code `PAUSE`.
4. Debounce duplicate ACCOFF broadcasts (common on some firmware variants).
5. Persist last-received ACCOFF timestamp.
6. Append ACCOFF diagnostics to log file.

ACCON flow:
1. Load saved package/state from ACCOFF.
2. Capture current foreground app package.
3. Start saved player app.
4. Wait configured delay (`ACC ON play delay`, default `2000` ms).
5. If player was playing before ACCOFF, send media code `PLAY`.
6. Execute configured ACCON startup targets in order (with per-target pauses).
7. Restore previous foreground app.
8. Clear saved ACCOFF state after successful ACCON handling.
9. Persist last-received ACCON timestamp.
10. Append ACCON diagnostics to log file.

## ACCON startup targets
Configured from `ACC ON startup targets` button in app settings screen.

Each target stores:
- Package name
- Optional activity name (blank = package default launcher activity)
- Pause after start (ms)

Targets are persisted via `SharedPreferences` JSON and loaded automatically on ACCON.

Editor behavior:
- Single scrollable list (no secondary manage list).
- Single-selection model.
- Actions: Add / Edit / Delete / Move up / Move down.
- Delete requires confirmation.
- Add/Edit flow:
  1. Select app.
  2. In one combined dialog choose activity (`Default launcher activity` preselected) and delay.

## Important Android 13 / FYT notes
A regular third-party app **cannot guarantee absolutely never stopping** on stock Android 13. This project uses the strongest non-root pattern available to apps:
- Foreground service with permanent notification.
- `START_STICKY` restart behavior.
- Boot/package-replaced auto-start receiver.
- Headless launcher entry suitable for FYT "autostart after sleep" trigger.
- WorkManager watchdog restart checks.

For best reliability on FYT head units, configure these manually:
- Configure FYT "autostart after sleep" to launch `FYT custom service` (headless starter).
- Use `FYT custom service settings` entry when you need to open configuration UI.
- Allow auto-start/background start in FYT vendor settings.
- Lock app/task in recent apps if launcher supports it.
- Keep notification permission/visibility enabled.

If you need near-system-daemon behavior, you typically need one of:
- System-app installation in `/system/priv-app`.
- Vendor firmware integration.
- Root + watchdog script/service manager.

## Required permissions and access
Declared in manifest:
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.RECEIVE_BOOT_COMPLETED`
- `android.permission.WAKE_LOCK`
- `android.permission.PACKAGE_USAGE_STATS`

Also required in system settings for full ACC logic:
- Notification access for `FYT custom service` (needed to inspect active media sessions reliably).
- Usage access for `FYT custom service` (needed to detect/restore foreground app).

Potentially required by device firmware:
- Vendor-specific "autostart" whitelist.
- Vendor-specific keep-alive whitelist / startup manager exception.

## ACC Event Logging
Log file location:
- `Documents/FYTService/FYTCustomService-acc.log`

Each line starts with timestamp format:
- `yyyy-MM-dd HH:mm:ss.SSS`

Logged details include:
- ACCON/ACCOFF receive events and duplicate-ignore events.
- ACCOFF active player detection and pause action.
- ACCON saved player launch attempt/result.
- ACCON PLAY sent/skipped with reason.
- Startup target actions per item: launched or skipped with reason (for example `already_running`).
- Previous foreground restore attempt/result.
## Where to add your code
Implement custom command logic in:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Method:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helpers are in:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`

