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
  - Skip target if package is already running.
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

ACCON flow:
1. Cancel any still-pending delayed ACCON work from an earlier ACCON cycle.
2. Capture current foreground app package before launching player/startup targets.
3. Load saved package from ACCOFF.
   - If no saved package exists and fallback player is configured, launch fallback player, wait ACCON delay, send `PLAY`.
4. Start saved player app.
5. Wait configured delay (`ACC ON play delay`, default `2000` ms).
6. Send media code `PLAY` to saved player.
7. Execute configured ACCON startup targets in order (with per-target pauses).
8. Restore previous foreground app.
   - If foreground detection is unavailable, fall back to launching HOME screen.
   - Delayed restore retries are tracked and canceled if a new ACCON/ACCOFF/reset flow arrives.
9. Clear saved ACCOFF package after successful ACCON handling.
10. Persist last-received ACCON timestamp.
11. Append ACCON diagnostics to log file.

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
- ACCON/ACCOFF receive events.
- ACCOFF active media-controller list, selected controller, active player detection, and pause action.
- ACCON saved player launch attempt/result.
- ACCON detected foreground app before startup-target flow.
- ACCON PLAY sent to saved player.
- Media command dispatch path (`transport` or `media_button+audio_manager`).
- Startup target actions per item: launched or skipped with reason (for example `already_running`).
- Previous foreground restore attempt/result and delayed restore retry results.
- Cancellation of pending ACCON delayed work when a newer ACCON, ACCOFF, or reset supersedes it.

## Changelog
### 2026-04-25
- Tracked and canceled delayed ACCON work:
  - pending delayed `PLAY`,
  - startup target continuation runnables,
  - delayed foreground restore retries.
- Added log entry when pending ACCON work is canceled by a newer ACCON, ACCOFF, or reset.
- ACCOFF now logs all active media controllers and the selected controller before saving the player package.
- ACCOFF media-controller selection now prefers currently playing/transitional sessions before paused/stopped sessions.
- Media command dispatch avoids triple-dispatch when media-session transport controls succeed.
- Media command logs now include the dispatch path used.
- Unified app settings/state storage through a shared device-protected storage helper, including migration from the previous normal app storage when needed.
- Boot and ACC receivers now use the same shared start-context helper for locked/unlocked boot phases.
- Reduced release APK size by enabling R8 minification and Android resource shrinking for `release` builds.
- Limited packaged resources to English via `resourceConfigurations`.
- Measured release APK size:
  - before optimization: `8,010,620` bytes (`7.64 MiB`)
  - after optimization with `minSdk 29`: `1,365,341` bytes (`1.30 MiB`)
- Tested `minSdk 33` for Android 13-only sizing; it saved only about `27,864` bytes versus `minSdk 29`, so `minSdk` remains `29`.

### 2026-04-04
- Added setting: fallback player package used on ACCON when no saved ACCOFF player exists.
- Fallback flow now mirrors normal saved-player ACCON behavior: launch player, wait configured delay, send `PLAY`.
- Settings dialog includes fallback-player app picker.
- Documented that app picker shows app icons.

### 2026-04-14
- Settings UI for fallback player now shows selected app **name + icon** (instead of raw package text).
- Added project handoff snapshot file for cross-PC continuation:
  - `CONTEXT_HANDOFF_2026-04-14.md`

### 2026-04-02
- Added per-target enable/disable checkbox in `ACC ON startup targets` editor.
- Checkbox state is persisted in startup-target storage.
- ACCON startup execution now skips disabled targets and logs `reason=disabled`.
- Added startup-list trigger-source logging so list start reason is visible in logs:
  - `acc_on_intent`
  - `boot_completed`
  - `fyt_startup_manager`

### 2026-04-01
- Updated boot receiver behavior:
  - `android.intent.action.BOOT_COMPLETED` now triggers `ACTION_ACC_ON` (same logic path as ACCON handling).
  - `android.intent.action.LOCKED_BOOT_COMPLETED` and `android.intent.action.MY_PACKAGE_REPLACED` keep using normal `ACTION_START`.

### 2026-03-29 (update 2)
- Logging now writes to a single required file path only:
  - `Documents/FYTService/FYTCustomService-acc.log`
- Main screen now shows the full active log file path in a dedicated line:
  - `Log file: ...`
- Foreground app detection for restore was hardened:
  - longer usage-events lookback,
  - additional `ACTIVITY_RESUMED` event support,
  - fallback to `UsageStats` `lastTimeUsed` when recent transition events are missing.
- ACCON capture for `Last active app before targets` now does not exclude this app package, improving manual test reliability from the settings screen.
- If ACCON restore has no detected previous foreground app, service now launches HOME instead of skipping restore.

### 2026-03-29
- Removed GLSX ACC aliases from receiver and manifest handling; only FYT actions are processed:
  - `com.fyt.boot.ACCON`
  - `com.fyt.boot.ACCOFF`
- Removed runtime ACC receiver in foreground service, keeping a single ACC receive path.
- Removed ACC duplicate-debounce gating and wake-lock wrapper around ACC handlers.
- Main screen player markers no longer show player state suffix (`(playing|paused|stopped|unknown)`); package names only.
- Added main-screen debug line: `Last active app before targets`.
- ACCON now logs detected foreground app before startup targets and uses that captured value for restore.

### 2026-03-28 (update 2)
- Improved ACC event debounce handling so `ACCON` is not incorrectly blocked after a recent `ACCOFF` (and vice versa).
- Updated ACCON behavior to always send `PLAY` to the saved player after launch (saved play-state from ACCOFF is no longer used for decision).
- Strengthened post-startup foreground restoration:
  - uses stronger launch flags for bring-to-front behavior,
  - adds delayed restore retries to handle FYT foreground race conditions.
- Swapped GUI timestamp row order in the left status column:
  - row 1: `Last ACC OFF`
  - row 2: `Last ACC ON`

### 2026-03-28
- Reworked ACC target editor action buttons to use real AppCompat icon drawables (no letter fallbacks), including day/night icon resources for visibility in both light and dark themes.
- Added ACC state reset action in GUI (`Reset ACC state`) in the same row as ACC test buttons.
- Added immediate UI refresh after ACC test actions so ACC/player markers update on screen without reopening settings.
- Changed status block layout to 2 rows x 2 columns:
  - left: `Last ACC ON`, `Last ACC OFF`
  - right: `Last saved player`, `Last started player`
- Extended player markers to include state display for both saved and started player:
  - `playing`, `paused`, `stopped`, `unknown`
- Updated ACC handling:
  - duplicate debounce is now 30 seconds per same event type (ACCON only vs ACCON, ACCOFF only vs ACCOFF),
  - ACCON still executes startup-target flow when no ACCOFF snapshot exists,
  - foreground app restore after startup-target execution was hardened.
- Reworked ACC logging backend:
  - single active log file in `Documents/FYTService/FYTCustomService-acc.log`,
  - size-based rotation at 100 kB with timestamped archive files.

### 2026-03-27
- Added runtime ACC receiver inside foreground service as an additional receive path for FYT/GLSX ACC broadcasts, with forwarding logs.
- Added two GUI test buttons on main screen to emulate ACC events: `Emulate ACC ON` and `Emulate ACC OFF`.
- Hardened ACC target editor action buttons for HU theme quirks:
  - kept themed vector drawables,
  - added compatible `drawableLeft` binding,
  - added visible text-symbol fallback labels.
- Increased app icon size in target edit dialog (activity + delay) to `64dp x 64dp`.

### 2026-03-26
- ACC ON target editor buttons now use app-owned vector pictograms with theme-aware tint (visible in both dark and light themes).
- App selection dialog now shows app icons next to app name and package.
- Edit flow for existing ACC ON target now updates only activity and delay without forcing app re-selection.
- Activity/delay dialog now shows selected app icon and app name.
- Main screen now shows `Last saved player` and `Last started player` near ACC timestamp lines.

## Where to add your code
Implement custom command logic in:
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`

Method:
- `private fun executeCommand(code: Int, arg1: Int)`

ACC/media helpers are in:
- `MediaControlHelper.kt`
- `MediaStateStore.kt`
- `ForegroundAppHelper.kt`

