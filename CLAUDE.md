# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android telemetry app (Kotlin, Jetpack Compose) for a **Voge 900DSX** motorcycle (LX900-A,
shares the BMW F900 platform / Loncin 4M96001 engine). It reads live engine data over a Bluetooth
ELM327 OBD-II adapter (standard OBD-II PIDs + BMW/Voge manufacturer UDS DIDs), combines it with
phone GPS/IMU, records rides to a local Room database at 5Hz, and backs up/restores to Google
Drive. See `README.md` for the full feature list and `docs/` for architecture detail:

- `docs/walkthrough.md` — current-state summary of every subsystem.
- `docs/implementation_plan.md` — the architectural decisions/patterns behind them, and why.
- `docs/confirmed_pids.md` — every OBD-II/UDS request verified working on this bike.

Keep these docs in sync when landing a feature/fix worth remembering — this is a public repo other
BMW-F900-platform owners may reference.

## Commands

```powershell
# Build
.\gradlew.bat assembleDebug

# Unit tests (JVM, no device needed)
.\gradlew.bat testDebugUnitTest

# Run a single unit test
.\gradlew.bat testDebugUnitTest --tests "com.example.mototelemetryapp.OBDParsingTest.testGearParsing"

# Instrumented tests (needs a connected device/emulator)
.\gradlew.bat connectedDebugAndroidTest

# Install to a specific device (adb.exe is not on PATH — see below)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
```

This repo is normally driven from **PowerShell**, not the Bash tool. `adb.exe` lives at
`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` and is not on PATH.

Two Android targets are commonly used side by side: the `Xiaomi_17T_Pro` emulator (`-s
emulator-5554`) for pure-UI changes, and a physical phone for anything touching real
Bluetooth/OBD/GPS/IMU — there's no way to emulate a real ELM327 adapter or a real motorcycle.
Check `adb devices` for the current serial rather than assuming one.

## Architecture

**Data flow is one-directional and StateFlow-based:** `TelemetryService` (a foreground service)
owns every live data source — `BluetoothOBDManager` (OBD/UDS over Bluetooth Classic RFCOMM),
`FusedLocationSource` (GPS), `OrientationManager` (phone IMU) — and exposes each signal as a
`MutableStateFlow`/`asStateFlow()` pair. `DashboardViewModel` collects those into its own mirrored
`StateFlow`s on `onServiceConnected`, and **cancels the collection without resetting the value** on
disconnect/unbind, so the UI doesn't flicker to blank while a ride keeps recording in the
background; it re-collects (picking up live values again) on the next connect. New live signals
follow this same Service → ViewModel → Compose UI plumbing pattern.

**Binding a service is not the same as starting it.** `bindService(BIND_AUTO_CREATE)` only runs
`onCreate()`; actual ride recording only starts via an explicit `startService()` →
`onStartCommand()`, guarded by a `trackingStarted` flag. Don't let something that only needs to
*read* service/device state (e.g. listing paired Bluetooth devices) trigger a bind as a side
effect — that has caused a real navigation bug before (see `docs/implementation_plan.md`).

**OBD polling is two-tiered**, both inside `BluetoothOBDManager`: a hot 100ms loop (`pollOnce()`)
for gauge-driving signals (RPM, Speed, Gear, Throttle, Brake, Lean, Coolant), and a slower ~5s tier
(`pollSlowPids()`) for everything else (odometer, fuel, battery, temps, trims, etc.). Results from
both are *merged*, not replaced, into the same map. Adding a new PID: decide which tier it belongs
in based on whether the UI actually needs 100ms freshness — every extra request in the hot loop
costs a full serial round trip on the same adapter.

**Every known OBD/UDS request lives in one place:** `BluetoothOBDManager.CONFIRMED_PID_MAP`
(header, command, signal name triples). Every parse function reports live success/failure via
`markPidStatus()` (`pidStatus: StateFlow<Map<String, Boolean>>`), which the Bike Info screen
renders as a live green/red/gray status table — the "confirmed" list reflects what's actually
answering right now, not a static claim. If you change how a signal is requested/parsed, keep this
map and the status reporting in sync.

**Diagnostic tooling is risk-tiered in the UI on purpose:** plain reads (standard PID scan,
manufacturer DID sweep, the raw CAN bus monitor) need no confirmation gate; the UDS Extended
Diagnostic Session probe changes ECU session state and is gated behind a confirmation dialog
telling the rider to only run it with the engine off, and always returns to Default Session before
finishing; unconfirmed CAN headers (`7E2`-`7E7`) are excluded from the default sweep entirely.
Match this tiering for any new diagnostic action — how intrusive the UI treatment is should scale
with how much the action could actually affect the ECU/vehicle, not just whether it works.

**Auto-start/ride continuation:** `ObdAutoStartReceiver` (manifest-registered `BroadcastReceiver`,
works even with the app process dead) starts `TelemetryService` when the remembered OBD device's
`ACL_CONNECTED` fires and the auto-start setting is on. `TelemetryService` also registers its own
dynamic receiver for `ACL_CONNECTED`/`ACL_DISCONNECTED` on that device: on disconnect it starts a
grace-period timer (configurable in Settings); reconnecting before it expires resumes into the
*same* session, otherwise the ride is finalized.

**Room DB** uses `fallbackToDestructiveMigration()` (dev-only) with a safety net that snapshots the
raw DB file before a version-bump wipe. New columns bump `DB_VERSION` and use `getColumnIndex`
(returning `-1` on a missing column) rather than `getColumnIndexOrThrow`, so restoring an older
Drive backup after a schema change degrades gracefully instead of crashing.

**Google Drive backup/restore** builds the Drive client directly from the OAuth access token
returned by Credential Manager / the Authorization API (not the legacy `GoogleAccountCredential`).
Requires a *Web application* OAuth client (not the Android client) as the `setServerClientId(...)`
token audience — see `README.md` setup steps for the full Cloud Console configuration.

**Bottom nav and the Home Quick Access grid share one tab order:** Panel → History → Analysis →
Bike Info → Settings. Keep them matching if either changes.

## Compose gotchas specific to this codebase

- `IntrinsicSize.Min`: a `Text`'s `minIntrinsicWidth` assumes wrapping is fine unless `maxLines =
  1, softWrap = false` is set on *every* `Text` in the intrinsically-sized subtree (labels **and**
  values) — the narrowest-width `Text` determines the forced column width and drags unguarded
  siblings down with it.
- `Arrangement.SpaceBetween` degenerates to zero gaps inside a `Row` sized via
  `IntrinsicSize.Min` (no slack space to distribute). Use `Arrangement.spacedBy(dp)` instead, or
  parameterize the arrangement per orientation.

## Verification discipline

UI- and navigation-affecting changes should be verified via `adb` (cold launch, exercise the actual
interaction path, screenshot) before being reported as done — a build+unit-test pass alone has
shipped a real navigation regression before (see `docs/implementation_plan.md`). Unit tests
(`OBDParsingTest`) cover parsing logic that's easy to break silently; run them after touching any
`parse*` function in `BluetoothOBDManager`.
