# FYT Custom Service - State Snapshot

## Project
- Path: `C:\Users\Igoreshka\Documents\FYT custom service`
- Target: FYT 7870 headunit, Android 13, landscape-only usage

## Implemented
- Foreground sticky service (`START_STICKY`) with persistent notification
- Custom command broadcast handling (`dev.igor.fytcustomservice.ACTION_COMMAND`)
- ACC broadcast handling:
  - `com.fyt.boot.ACCOFF`
  - `com.fyt.boot.ACCON`
- ACCOFF behavior:
  - Capture current media session package + playing state
  - Persist state synchronously (`SharedPreferences.commit()`)
  - Send media `PAUSE`
- ACCON behavior:
  - Load last saved media state
  - Capture current foreground app package
  - Launch saved player package
  - Delay configurable via settings (`500..10000` ms, default `2000`)
  - If previously playing, send media `PLAY`
  - Restore previously foreground app
  - Clear saved media snapshot after ACCON flow completes
- ACC duplicate event protection:
  - Debounce ACCON/ACCOFF events within a short window to avoid duplicate handling
- Short wake lock around ACC handling and delayed PLAY/restore path
- Safer app launch path with exception handling
- Boot auto-start receiver enabled (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`)
- Minimal GUI with start/stop, settings dialog, test command
- Setup prompts in app for required accesses:
  - Notification Listener access
  - Usage Access
- Adaptive launcher icon created (android + media/player motif)
- Manifest set to landscape activity and launcher icons
- README updated

## Important Decisions
- Battery optimization handling intentionally removed (per user request; FYT battery state unreliable)
- Reliance on:
  - `MediaSessionManager` active sessions (via Notification Listener access)
  - `UsageStatsManager` events (via Usage Access)

## Key Files
- `app/src/main/java/dev/igor/fytcustomservice/FytForegroundService.kt`
- `app/src/main/java/dev/igor/fytcustomservice/AccPowerReceiver.kt`
- `app/src/main/java/dev/igor/fytcustomservice/MediaControlHelper.kt`
- `app/src/main/java/dev/igor/fytcustomservice/MediaStateStore.kt`
- `app/src/main/java/dev/igor/fytcustomservice/ForegroundAppHelper.kt`
- `app/src/main/java/dev/igor/fytcustomservice/FytNotificationListenerService.kt`
- `app/src/main/java/dev/igor/fytcustomservice/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `README.md`

## Required On-Device Setup
- Enable Notification access for this app
- Enable Usage access for this app
- Add app to FYT autostart / keep-alive whitelist (vendor-specific setting)

## Build Status
- Last checks passed:
  - `:app:compileDebugKotlin`
  - `:app:processDebugResources`
- Current environment status:
  - Build verification now blocked locally by missing Java 17 runtime (only Java 11 found on this machine)

## Suggested Next Validation
1. Install on headunit.
2. Verify app receives ACCOFF/ACCON broadcasts from firmware.
3. Test with 2-3 media apps (e.g., Spotify, Poweramp, YT Music).
4. Confirm ACCOFF pauses correct session.
5. Confirm ACCON restarts player, waits configured delay, then returns foreground app.
6. Validate duplicate ACC broadcasts are ignored (no duplicate PLAY/PAUSE).
7. Capture logcat around ACC events for edge cases.
