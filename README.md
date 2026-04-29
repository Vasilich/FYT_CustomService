# FYT custom service

`FYT custom service` is an Android app designed for FYT-based Android 13 head units (UIS7870 class devices). It runs a foreground service with sticky restart behavior and can execute custom code paths based on numeric parameters received through intents.

## What it does
- Starts a persistent foreground service (`START_STICKY` + ongoing notification).
- Uses a headless launcher activity for FYT autostart triggers (no settings UI shown if service is already running).
- Keeps manual settings access via a separate launcher entry: `FYT custom service settings`.
- Listens for custom broadcast intents with numeric parameters.
- Dispatches logic by command code in `FytForegroundService.executeCommand(...)`.
- Handles FYT ACC events:
  - `com.fyt.boot.ACCOFF`: save current media app package, then send `PAUSE`.
  - `com.fyt.boot.ACCON`: launch saved player, wait configurable delay (default 2 seconds), send `PLAY`, then restore the app that was foreground before starting the player.
- Supports ACCON startup target list (persisted on disk):
  - Configure ordered app/activity starts.
  - Per-target pause after launch.
  - Optional default launcher activity (no explicit activity required).
  - Skip target if package is already running (multi-signal running check).
  - Per-target enabled checkbox (persisted).
- Provides a minimal GUI to:
  - Start/stop the service.
  - Show service status (`running`/`stopped`) and last received ACCON/ACCOFF timestamps.
  - Change command action string.
  - Toggle auto-start on boot.
  - Set ACCON play delay in milliseconds (500-10000 ms).
  - Configure fallback player package for ACCON when no saved player exists.
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
1. Read active media sessions and log all detected media controllers.
2. Select the best media controller by state priority: playing, transitional states, paused, then stopped/none.
3. Persist selected `packageName` using synchronous commit.
4. Send media code `PAUSE`.
   - Use media-session transport controls when the selected package has an active controller.
   - Fall back to package media-button broadcast plus global `AudioManager` media key dispatch only when transport controls are unavailable.
5. Persist last-received ACCOFF timestamp.
6. Append ACCOFF diagnostics to log file.

Note on play-state handling:
- Play-state detection from ACCOFF is intentionally not used anymore.
- Reason: on tested FYT firmware, media is often already paused/stopped before ACCOFF broadcast is observed, so stored state was unreliable.
- Decompiled FYT chain confirms ACCOFF broadcast dispatch (`C2444q0.m7483b(0)`), and upstream ACC transition handler (`C2692d.m10441i`) performs mute/LCDC transitions before that broadcast.
- No direct explicit media `PAUSE/STOP` call was found in that exact ACCOFF broadcast dispatch chain; this is an implementation-level inference from the inspected code paths.
- Decompiled references used for this conclusion:
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\m1\q0.java` (`b(int i2)`, lines ~447-454): sends ACC power broadcasts from the FYT service layer.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p043m1\C2444q0.java` (`m7483b(int i2)`, lines ~512-519): same ACCON/ACCOFF dispatch in obfuscated namespace.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p058s0\C2692d.java` (`m10441i(int i2)`, lines ~3258-3291): ACC state update path, calls mute/LCDC handling and then `C2444q0.m7483b(i2)`.
  - `D:\SinoSmart\Decompiled\com.syu.ms\app\src\main\java\p043m1\C2444q0.java` (`m7494m(int i2)`, lines ~586-617; `m7506y(int i2)`, lines ~993-1013): LCDC/amp mute control on ACC transitions.

Mute AMP logic (what it actually does):
- FYT mute-AMP path is hardware-level output muting, not media-app playback control.
- In ACC transition flow (`C2692d.m10441i`), the code calls `C2444q0.m7506y(...)`, which writes amp mute/unmute commands through JNI/native layer (`ToolsJni.cmd_6_mute_amp(...)` or `ControlNative.fytMuteAMP(...)` on some variants).
- Practical effect: audio output path is muted/unmuted globally at amp/MCU level.
- This is separate from media-session transport control (`PLAY/PAUSE/STOP`) and does not by itself prove player state transitions.

Receiver robustness notes:
- `AccPowerReceiver` is marked `directBootAware=true` (can receive pre-unlock phase).
- Receiver first tries `startForegroundService(...)` and falls back to `startService(...)` if needed by firmware/runtime constraints.
- Foreground service also registers a runtime ACC receiver for `com.fyt.boot.ACCON` / `com.fyt.boot.ACCOFF` while service is alive.
- `ACTION_START` recovery path:
  - if `last_acc_off_ms > last_acc_on_ms`, service runs ACCON-equivalent recovery flow and logs `reason=missed_acc_on_recovery`.
  - recovery source tag is appended to trigger source (for example `missed_acc_on_recovery:watchdog`).
- Runtime receiver path is logged as:
  - `RuntimeAccReceiver received action=com.fyt.boot.ACCON`
  - `RuntimeAccReceiver received action=com.fyt.boot.ACCOFF`

ACCON flow:
1. Ignore ACCON if:
   - another ACCON sequence is already in progress, or
   - the previous ACCON sequence started less than 2 minutes ago.
2. Cancel any still-pending delayed ACCON work from an earlier ACCON cycle.
3. Capture current foreground app package before launching player/startup targets.
4. Load saved package from ACCOFF.
   - If no saved package exists and fallback player is configured, launch fallback player, wait ACCON delay, send `PLAY`.
5. Start saved player app.
6. Wait configured delay (`ACC ON play delay`, default `2000` ms).
7. Send media code `PLAY` to saved player.
8. Execute configured ACCON startup targets in order (with per-target pauses).
9. Restore previous foreground app.
   - If foreground detection is unavailable, fall back to launching HOME screen.
   - Delayed restore retries are tracked and canceled if a new ACCON/ACCOFF/reset flow arrives.
10. Clear saved ACCOFF package after successful ACCON handling.
11. Persist last-received ACCON timestamp.
12. Append ACCON diagnostics to log file.

## ACCON startup targets
Configured from `ACC ON startup targets` button in app settings screen.

Each target stores:
- Package name
- Optional activity name (blank = package default launcher activity)
- Pause after start (ms)
- Enabled flag (checkbox in editor, persisted)

Targets are persisted via device-protected `SharedPreferences` JSON and loaded automatically on ACCON.

Editor behavior:
- Single scrollable list (no secondary manage list).
- Single-selection model.
- Actions: Add / Edit / Delete / Move up / Move down.
- Quick enable/disable via checkbox per row (without removing target).
- App picker lists app icon, app name, and package.
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
- `android.permission.POST_NOTIFICATIONS`

Also required in system settings for full ACC logic:
- Notification access for `FYT custom service` (needed to inspect active media sessions reliably).
- Usage access for `FYT custom service` (needed to detect/restore foreground app).
- If Notification Listener access is blocked after install on Android 13, open App info for this app and allow restricted settings first, then enable Notification access.

Potentially required by device firmware:
- Vendor-specific "autostart" whitelist.
- Vendor-specific keep-alive whitelist / startup manager exception.

## ACC Event Logging
Log file location:
- `Documents/FYTService/FYTCustomService-acc.log`
- On Android 10+ (`targetSdk 33`), writing uses MediaStore scoped-storage APIs for this path.
- On older Android versions, writing uses direct file append in public Documents.

Each line starts with timestamp format:
- `yyyy-MM-dd HH:mm:ss.zzz` (3-digit milliseconds, for example `2026-04-28 14:37:05.042`)

Logged details include:
- ACCON/ACCOFF receive events.
- `ACTION_START` missed-ACCON recovery decision (`reason=missed_acc_on_recovery` when triggered).
- ACCOFF active media-controller list, selected controller, active player detection, and pause action.
- ACCON saved player launch attempt/result.
- ACCON detected foreground app before startup-target flow.
- ACCON PLAY sent to saved player.
- Media command dispatch path (`transport` or `media_button+audio_manager`).
- Startup target actions per item: launched or skipped with reason (for example `already_running`).
  - Includes running-check source details (`running_app_processes`, `foreground_usage_event`, `recent_foreground_without_background`, `not_detected`).
- Previous foreground restore attempt/result and delayed restore retry results.
- Cancellation of pending ACCON delayed work when a newer ACCON, ACCOFF, or reset supersedes it.
- ACCON dedup/guard events:
  - `reason=sequence_in_progress`
  - `reason=duplicate_within_window` (2-minute window)
- Explicit GUI-state marker writes as `STATE ...` entries:
  - `last_acc_on_ms`, `last_acc_off_ms`
  - `last_acc_on_sequence_started_ms`
  - `last_saved_player`, `last_saved_player_state`
  - `last_started_player`, `last_started_player_state`
  - `last_active_app_before_startup_targets`

## Changelog
See [CHANGELOG.md](CHANGELOG.md).

## Where to add your code
Implement custom command logic in:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Method:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helpers are in:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`

