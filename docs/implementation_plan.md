# Settings, Bike Info, Auto-Start, Diagnostics & Drive Backup/Restore Plan

This plan covers moving settings into their own tab, starting/continuing rides automatically
based on the OBD adapter's connection state, reading and clearing check-engine codes, a new Bike
Info diagnostics tab, and fixing + extending Google Drive backup into backup/restore.

## User Review Required

> [!IMPORTANT]
> - **Ride continuation grace period:** configurable per user (Settings), not hardcoded — default
>   10 minutes. Below that, an OBD reconnect resumes the same ride; above it, a new ride starts.
> - **Restore semantics:** both Replace (wipe local, insert backup) and Merge (skip sessions whose
>   `startTime` already exists locally, insert the rest) are offered as a choice at restore time,
>   not a single fixed behavior.
> - **DTC clearing is destructive** (resets ECU readiness monitors) and sits behind a second
>   confirmation dialog, not a single tap.

## Proposed Changes

### [Settings] - Dedicated Tab

#### [NEW] [SettingsScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/SettingsScreen.kt)
- Auto-start toggle, ride-continuation grace-period stepper (shown only when auto-start is on),
  battery-optimization exemption status/retry, language picker.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Add "Settings" to the bottom nav; hoist the one-time battery-optimization prompt out of the home
  screen to the top level so it fires regardless of the active tab.
- Strip auto-start/grace-period/language controls out of the home screen (`MainScreen`) now that
  Settings owns them.

### [Automation] - Auto-Start & Ride Continuation

#### [NEW] [ObdAutoStartReceiver.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ObdAutoStartReceiver.kt)
- Manifest-registered receiver for `ACTION_ACL_CONNECTED`; starts `TelemetryService` for the
  remembered OBD device address when the auto-start setting is on. Works cold (app not running)
  since ACL-connect broadcasts are exempt from background-broadcast restrictions.

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- Guard `onStartCommand` with a `trackingStarted` flag so a repeated start intent (e.g. a flaky
  reconnect while already tracking) doesn't spin up a second session/telemetry loop.
- Register a dynamic receiver for `ACL_CONNECTED`/`ACL_DISCONNECTED` on the tracked device. On
  disconnect: drop the OBD link, start a grace-period timer read from Settings. On reconnect
  before it expires: cancel the timer, reconnect OBD, keep writing into the same `currentSessionId`.
  On expiry: `stopSelf()` to finalize the ride.

### [Diagnostics] - Check Engine (Mode 01/03/04)

#### [MODIFY] [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt)
- Poll Mode 01 PID 01 (MIL status + DTC count) every ~5s in the existing data loop; fetch Mode 03
  (stored DTCs) only when the count says there's something there. Decode per SAE J2012.
- Add `clearDtcs()` (Mode 04) checking for the `44` positive-response byte.
- Add `CONFIRMED_PID_MAP`: the header/command → signal mappings already implemented, for the Bike
  Info reference table to render without duplicating this knowledge in the UI layer.

#### [MODIFY] [DashboardScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/DashboardScreen.kt)
- `CheckEngineBadge`: red badge + DTC list dialog + "Clear Codes" → `ClearDtcsConfirmDialog`
  (extracted as its own composable so Bike Info can reuse it).

### [UI] - Bike Info Tab

#### [NEW] [BikeInfoScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/BikeInfoScreen.kt)
- Identity card (model/engine + live odometer), engine-health stat grid, permanent diagnostics
  card (reusing `ClearDtcsConfirmDialog`), confirmed-PID reference table, sweep shortcut reusing
  the existing `ObdSweepDialog`.

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt) / [DashboardViewModel.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/DashboardViewModel.kt)
- Expose the raw OBD PID map (`obdRawData`) end to end, since the odometer isn't part of the
  recorded `TelemetryRecord` shape and Bike Info needs it live.

### [Cloud] - Google Drive Backup & Restore

#### [MODIFY] [GoogleDriveManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/GoogleDriveManager.kt)
- Replace `GoogleAccountCredential` (legacy, Account-Manager-based, incompatible with the modern
  sign-in flow already in use) with a Drive client built directly from the OAuth access token.
- `uploadDatabase()`: checkpoint WAL before copying the db file.
- Add `listBackups()` (nullable return — null means the fetch failed, distinct from a successful
  empty list) and `downloadBackup()`.

#### [NEW] [BackupRestore.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/BackupRestore.kt)
- `restoreFromBackupFile()`: reads the downloaded backup via raw SQLite (it's a standalone copy,
  not the live Room database) and writes into the current database via the normal DAO, either
  wiping local data first (Replace) or skipping sessions that already exist locally by `startTime`
  (Merge).

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Consolidate the sign-in + authorization dance into one `requestDriveAccessToken(onToken,
  onFailure)` helper shared by Backup and Restore, instead of duplicating it.
- Add a Restore button + dialog (pick backup by date, Replace/Merge choice, confirm).
- **Google Cloud Console (external, not code):** create a Web-application OAuth client in the same
  project as the existing Android client and point `setServerClientId(...)` at it — the Android
  client ID doesn't work as the token audience Credential Manager expects. Add every account that
  needs to sign in under Audience → Test users while the consent screen is in Testing status.

## Verification Plan

### Manual Verification
- Toggle every Settings control and confirm the app reflects the change immediately.
- Power an OBD adapter on with auto-start enabled; confirm tracking starts without opening the app.
- Cycle OBD power within and beyond the grace period; confirm same-session continuation vs. a new
  session, respectively, by checking which session new records land in.
- Trigger a DTC (or use a bike/simulator that reports one); confirm the badge, the code list, and
  that Clear Codes requires the second confirmation before it actually clears.
- Open Bike Info with OBD both connected and disconnected; confirm no crash and sensible
  placeholder values when disconnected.
- On a physical device: Backup, then Restore with Merge, and confirm a session absent locally
  before the restore is present afterward (not just a "success" toast).
