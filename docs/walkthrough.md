# Settings, Bike Info, Auto-Start, Diagnostics & Drive Backup/Restore Walkthrough

This round of work reorganizes app-wide settings into their own tab, adds hands-off ride tracking
tied to the OBD adapter's connection state, surfaces check-engine diagnostics, adds a Bike Info
tab documenting what the app actually reads off the ECU, and fixes (then extends) Google Drive
backup into a full backup/restore flow.

## Changes Made

### 1. Settings Tab
- New bottom-nav tab (`ui/SettingsScreen.kt`) holding every app-wide setting instead of
  scattering toggles across the home screen: auto-start on OBD connect, the ride-continuation
  grace period, battery-optimization exemption status, and language.
- The one-time battery-optimization prompt was hoisted out of the home screen into `MainActivity`
  top level so it fires on app launch regardless of which tab is showing.

### 2. Auto-Start Tracking & Ride Continuation
- `ObdAutoStartReceiver` (manifest-registered `BroadcastReceiver`) starts `TelemetryService` when
  `ACTION_ACL_CONNECTED` fires for the previously-connected OBD device, if the setting is on. It
  works even when the app process isn't running, since ACL-connect broadcasts are exempt from
  Android's background-broadcast restrictions.
- `TelemetryService` registers its own dynamic receiver for `ACL_CONNECTED`/`ACL_DISCONNECTED` on
  the same device. On disconnect it drops the stale OBD link and starts a grace-period timer
  (configurable in Settings, default 10 min); reconnecting before it expires resumes recording
  into the *same* session, otherwise the ride is finalized via `stopSelf()`.
- Fixed a duplicate-session bug: `TelemetryService.onStartCommand` used to spin up a brand new
  session and telemetry loop on every call, so a flaky Bluetooth reconnect while already tracking
  would overlap sessions. Guarded with a `trackingStarted` flag.

### 3. Check-Engine Diagnostics (OBD Mode 01/03/04)
- `BluetoothOBDManager` polls Mode 01 PID 01 every ~5s for MIL status and DTC count, then Mode 03
  for the actual codes (decoded per SAE J2012, e.g. `0133` → `P0133`) only when there's something
  to fetch.
- A red "CHECK ENGINE" badge appears next to the OBD status badge on the Panel whenever the MIL is
  on; tapping it lists the stored codes and offers "Clear Codes" (Mode 04) behind a second
  confirmation dialog, since it resets ECU readiness monitors.
- `ClearDtcsConfirmDialog` was extracted out of the dashboard's `CheckEngineBadge` so the Panel
  badge and the Bike Info tab (below) share one implementation instead of duplicating it.

### 4. Bike Info Tab
- New tab (`ui/BikeInfoScreen.kt`) between History and Analysis: model/engine identity plus the
  live ECU odometer (piped through a new `obdRawData` flow from `BluetoothOBDManager` through
  `TelemetryService`/`DashboardViewModel` — the odometer isn't part of the recorded
  `TelemetryRecord` shape, so it needed its own path), an engine-health stat grid (coolant, fuel
  level, fuel rate, plus a `--` placeholder for battery voltage since no PID is mapped for it yet),
  a permanent diagnostics card, a reference table of confirmed CAN header/DID → signal mappings
  (`BluetoothOBDManager.CONFIRMED_PID_MAP`), and a shortcut into the existing diagnostic sweep tool.

### 5. Google Drive Backup & Restore
- **Backup was broken.** It mixed the modern Credential Manager / Authorization API sign-in flow
  with the legacy `GoogleAccountCredential` (Account-Manager-based) path for the actual Drive API
  calls — two auth systems that don't share state — and one code path used a hardcoded placeholder
  `Account("authorized", "com.google")` instead of the signed-in user. `GoogleDriveManager` now
  builds the Drive client directly from the OAuth access token the Authorization API returns.
- Added a WAL checkpoint (`AppDatabase.checkpoint()`) before copying the database file, since Room
  defaults to WAL journal mode and a backup taken right after a ride could otherwise miss writes
  still sitting in the `-wal` file.
- **Restore, new:** lists backups from the Drive appDataFolder, lets the user pick one plus a
  Replace/Merge choice, downloads it, and imports it via raw SQLite reads into the live Room
  database (`data/BackupRestore.kt`) — Merge skips sessions whose `startTime` already exists
  locally, Replace wipes local data first.
- The restore dialog distinguishes loading / error / empty states (`driveBackups: List<...>?`,
  null = not loaded or failed) so a failed sign-in doesn't look like "no backups found".
- **The actual root cause of the remaining failures was Google Cloud Console configuration, not
  code:** `setServerClientId(...)` was pointed at the app's *Android* OAuth client, but Credential
  Manager's Google Sign-In requires a *Web application* client ID there regardless of platform —
  using the Android one fails with `[28444] Developer console is not set up correctly`. A Web
  client was created in the same project and the code updated to reference it. Separately, the
  OAuth consent screen was in "Testing" status with zero test users, which blocks sign-in with
  `403: access_denied` even for the project owner — fixed by adding the account under
  Audience → Test users.

### 6. Housekeeping
- Removed `.artifacts/` (leftover session-scratch output from an earlier tool, three UUID-named
  folders of auto-generated planning docs) from version control and added it to `.gitignore`.

## Verification Steps

1. **Settings:** Toggle auto-start, adjust the grace-period stepper, confirm the battery banner
   reflects actual exemption status, switch language and confirm every screen updates.
2. **Bike Info:** Open the tab with OBD disconnected (should show `--`/zeros, not crash), connect
   OBD and confirm coolant/fuel/odometer update live, tap the sweep shortcut and confirm the
   existing sweep dialog opens.
3. **Check Engine:** Ride with an active DTC (or simulate), confirm the badge appears with the
   correct code(s), tap Clear Codes, confirm the two-step confirm dialog and that the badge clears.
4. **Auto-start/continuation:** Power the OBD adapter on with the setting enabled and confirm
   tracking starts unprompted; power off and back on within the grace period and confirm records
   continue into the same session (check `sessionId` doesn't change); exceed the grace period and
   confirm a new session starts on reconnect.
5. **Backup/Restore (verified end-to-end on a physical device, not just the emulator):** Tap
   Backup, sign in, confirm `GoogleDriveManager: Backup successfully completed.` in logcat. Tap
   Restore, confirm the dialog lists backups by date, pick one with Merge, confirm
   "Geri yükleme başarılı!" and that a previously-missing session now appears in "Last Ride".

## Project Status
- **Auth:** Modern Credential Manager + Authorization API, using a Web OAuth client as the token
  audience (not the Android client) — this was the load-bearing fix.
- **Backup/Restore:** Google Drive appDataFolder, WAL-checkpointed backups, Merge/Replace restore.
- **Diagnostics:** MIL/DTC read (Mode 01/03) and clear (Mode 04), reference PID map, sweep tool.
- **Automation:** OBD-connect-triggered auto-start with grace-period ride continuation.
- **Analytics:** Session tracking with Vico line charts and absolute fuel metrics (prior work).
- **Core:** Professional OBD2 (UDS) & IMU integration with Dual Distance tracking (prior work).
