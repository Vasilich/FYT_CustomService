# Changelog

### 2026-04-28
- Re-added runtime ACC receiver inside foreground service to improve ACCON/ACCOFF capture reliability while service is running.
- Kept static manifest receiver as cold-start path; both receiver paths now log receive source.
- Switched ACC log writing on Android 10+ to MediaStore append/rotate for `Documents/FYTService/FYTCustomService-acc.log` compatibility under scoped storage.
- Fixed MediaStore log-file lookup to reuse existing `FYTCustomService-acc.log` (prevents duplicate files like `FYTCustomService-acc.log (1)`, `(2)` on repeated starts).
- Added startup log health-check entry from main screen open (`MAIN_ACTIVITY started; log write health check`) so log-path write status can be validated without waiting for ACC events.
- Updated log timestamp output to fixed `yyyy-MM-dd HH:mm:ss.zzz` format (3-digit milliseconds).
- Added explicit `STATE ...` log lines for GUI-related markers (`last_acc_on/off`, saved/started player and states, foreground-before-startup-targets).
- Permission/access dialog updates:
  - opens automatically on resume when required accesses are missing,
  - includes `App info` button for restricted-settings path,
  - validates Notification Listener access against exact listener service component.

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
