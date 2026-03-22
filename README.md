# FYT custom service

`FYT custom service` is an Android app designed for FYT-based Android 13 head units (UIS7870 class devices). It runs a foreground service with sticky restart behavior and can execute custom code paths based on numeric parameters received through intents.

## What it does
- Starts a persistent foreground service (`START_STICKY` + ongoing notification).
- Listens for custom broadcast intents with numeric parameters.
- Dispatches logic by command code in `FytForegroundService.executeCommand(...)`.
- Handles FYT ACC events:
  - `com.fyt.boot.ACCOFF`: save current media app package + playing state, then send `PAUSE`.
  - `com.fyt.boot.ACCON`: launch saved player, wait configurable delay (default 2 seconds), send `PLAY`, then restore the app that was foreground before starting the player.
- Provides a minimal GUI to:
  - Start/stop the service.
  - Change command action string.
  - Toggle auto-start on boot.
  - Set ACCON play delay in milliseconds (500-10000 ms).

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

ACCON flow:
1. Load saved package/state from ACCOFF.
2. Capture current foreground app package.
3. Start saved player app.
4. Wait configured delay (`ACC ON play delay`, default `2000` ms).
5. If player was playing before ACCOFF, send media code `PLAY`.
6. Restore previous foreground app.
7. Clear saved ACCOFF state after successful ACCON handling.

## Important Android 13 / FYT notes
A regular third-party app **cannot guarantee absolutely never stopping** on stock Android 13. This project uses the strongest non-root pattern available to apps:
- Foreground service with permanent notification.
- `START_STICKY` restart behavior.
- Boot/package-replaced auto-start receiver.

For best reliability on FYT head units, configure these manually:
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
- Vendor-specific keep-alive whitelist / startup manager exception.\n
## Where to add your code
Implement custom command logic in:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Method:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helpers are in:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`

