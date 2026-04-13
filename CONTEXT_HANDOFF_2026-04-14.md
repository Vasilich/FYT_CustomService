# CONTEXT HANDOFF (Portable)
Date: 2026-04-14
Project: FYT custom service
Repo path used here: `C:\Users\Igoreshka\Documents\FYT custom service`

## How to use this on another PC
1. Copy the whole project folder (including `.git`) to the new PC.
2. Open this repository and read this file first.
3. Run `git log --oneline -n 20` and compare with "Recent commits" below.
4. If you also copied uncommitted changes, check `git status` and continue.

## Current branch/state
- Branch: `master`
- Recent HEAD commit (committed): `3ed3db7` (`Add ACCON fallback player setting and document picker icons`)
- Working tree currently has uncommitted changes in:
  - `app/src/main/java/dev/igor/fytcustomservice/AccOnStartupConfig.kt`
  - `app/src/main/java/dev/igor/fytcustomservice/MainActivity.kt`
  - `app/src/main/res/layout/dialog_settings.xml`

These uncommitted changes are intentional and correspond to the latest request:
- show fallback player by app name (not package) in settings
- show fallback player icon in settings row

## Core behavior decisions in this project
1. ACC actions handled by app:
- Reacts only to FYT actions:
  - `com.fyt.boot.ACCON`
  - `com.fyt.boot.ACCOFF`
- GLSX subscription/reaction is removed by user request.

2. Duplicate/reentrance behavior:
- No debounce and no lock-based reentrance blocking for ACC events.
- Every received FYT ACC broadcast is processed.

3. ACCON startup trigger-source tracking:
- Service accepts `EXTRA_TRIGGER_SOURCE` and logs startup reason (`STARTUP_LIST triggerSource=...`).
- Sources:
  - `acc_on_intent`
  - `boot_completed`
  - `fyt_startup_manager`
  - `boot_misc`
  - `unknown`

4. Boot behavior:
- `BOOT_COMPLETED` starts ACCON logic (`ACTION_ACC_ON`).
- `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` use `ACTION_START`.
- FYT startup manager launch (normal shortcut -> `StartupActivity`) sends source `fyt_startup_manager` and service runs ACCON-equivalent startup flow.

5. Foreground restore behavior:
- Foreground app is captured before starting saved/default player.
- If foreground app is missing at restore time, service falls back to launching HOME.

6. Logging:
- Log path target: `Documents/FYTService/FYTCustomService-acc.log`
- Main screen displays active log path.
- Timestamp format: `yyyy-MM-dd HH:mm:ss.SSS`

7. ACCOFF player-state telemetry:
- ACCOFF captures current player package and player state before pause.
- Persisted and shown on main screen for saved player indication.

8. Startup targets list:
- Targets support per-item enabled checkbox.
- Enabled state is persisted.
- Disabled targets are skipped with log reason `disabled`.
- Storage was adjusted to avoid split stores between credential/device-protected startup paths.

9. Fallback player (new feature):
- Setting exists for fallback player package when no saved ACCOFF player exists.
- ACCON behavior when no saved player and fallback configured:
  - launch fallback player
  - wait ACCON delay (default 2000 ms)
  - send PLAY
  - continue startup-target flow

## Key files/modules
- Service flow: `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`
- ACC receiver: `app/src/main/java/dev/igor/fytcustomservice/AccPowerReceiver.kt`
- Boot receiver: `app/src/main/java/dev/igor/fytcustomservice/BootReceiver.kt`
- Startup launcher activity: `app/src/main/java/dev/igor/fytcustomservice/StartupActivity.kt`
- Settings storage: `app/src/main/java/dev/igor/fytcustomservice/ServiceSettings.kt`
- Startup targets model/store: `app/src/main/java/dev/igor/fytcustomservice/AccOnStartupConfig.kt`
- Main UI logic: `app/src/main/java/dev/igor/fytcustomservice/MainActivity.kt`
- Settings UI layout: `app/src/main/res/layout/dialog_settings.xml`
- Main layout: `app/src/main/res/layout/activity_main.xml`
- Event/log state storage: `app/src/main/java/dev/igor/fytcustomservice/AccEventTelemetry.kt`

## Recent commits (newest first)
- `3ed3db7` Add ACCON fallback player setting and document picker icons
- `ac2f84a` Add startup target toggles and trigger-source logging
- `7fb33fa` Trigger ACCON flow on BOOT_COMPLETED
- `862ee51` Improve ACCOFF state telemetry and launcher shortcut behavior
- `aec1b89` Fallback to HOME when ACCON foreground restore is unavailable
- `1a72239` Fix log path visibility and foreground app detection
- `fd122cc` Fix ACC flow, UI markers, and logging reliability

## Practical testing checklist
1. Verify ACCOFF reception by checking log for:
- `AccPowerReceiver received action=com.fyt.boot.ACCOFF ...`
- `RECEIVED com.fyt.boot.ACCOFF`
2. Verify ACCON source logging:
- `RECEIVED ACCON source=...`
- `STARTUP_LIST triggerSource=...`
3. Verify fallback player path:
- Clear/reset ACC state so no saved player exists.
- Configure fallback player in settings.
- Trigger ACCON and confirm launch + delayed PLAY in logs.
4. Verify startup list enabled checkboxes:
- Disable one app and ensure log shows `reason=disabled`.

## Notes from user preferences/decisions
- Do not react to `com.glsx.boot.*`.
- Keep startup app list persistent and manageable via enable/disable checkboxes.
- Settings shortcut should open GUI; normal shortcut should only start service.
- Settings UI should show fallback player by app name (and now icon).

## If context seems inconsistent on another PC
Run:
- `git status --short`
- `git log --oneline -n 20`
- `./gradlew :app:assembleDebug` (or `gradlew.bat` on Windows)
Then compare with this file and continue.
