# Development Walkthrough

A current-state summary of every major subsystem in the app, organized by area rather than by
when it was built. Kept up to date as features change — see `docs/implementation_plan.md` for the
architectural patterns behind these.

## 1. OBD/UDS Data Acquisition (`BluetoothOBDManager`)

- Connects to a Bluetooth Classic (RFCOMM) ELM327-compatible adapter, remembers the last device
  picked from the Panel's OBD badge dropdown, and falls back to auto-detecting a paired device by
  name hint (`OBD`, `ELM327`, `VLINK`, `ICAR`, `VGATE`, `OBDLINK`, etc.) if none is remembered.
- A 100ms data loop (`pollOnce()`) requests the hot, gauge-driving signals every tick: RPM, Speed,
  Gear, Throttle, front/rear Brake, Lean angle (bike IMU), Coolant temp.
- A slower tier (`pollSlowPids()`, same ~5s cadence as DTC polling) covers PIDs that don't need
  100ms freshness: odometer, distance since DTC clear, fuel rate/level, battery voltage, intake/
  ambient temp, engine load, MAP pressure, timing advance, engine runtime, distance with MIL on,
  catalyst temp (both banks), fuel trims, O2 sensor voltage/trim, absolute load, equivalence ratio,
  throttle positions, pedal positions, commanded throttle actuator. Results from both tiers are
  merged (not replaced) into the same `Map<String, Int>` so slow-tier values persist between fast
  refreshes.
- **Confirmed sensor map:** every request this manager knows how to make lives in one place,
  `BluetoothOBDManager.CONFIRMED_PID_MAP` — a `(header, command, signal name)` triple. Every parse
  function reports whether its last response actually matched what was expected via
  `markPidStatus()`, exposed as `pidStatus: StateFlow<Map<String, Boolean>>`. The Bike Info screen
  renders this live, so the "confirmed" table reflects what's actually answering right now, not a
  static claim.
- **Dead-link detection:** `sendCommand()`/`readResponse()` intentionally swallow `IOException`
  internally so one unsupported PID doesn't kill the polling loop — but that also means a genuinely
  dead socket (adapter losing power when the ignition switches off) never used to surface. A
  `consecutiveWriteFailures` counter now trips `disconnect()` after 8 failures in a row (roughly
  one `pollOnce()` cycle), since a *write* failure (unlike a read timeout) only happens when the
  RFCOMM link itself is broken.
- **Serial-read discipline:** `readResponse()` enforces its 2s timeout by polling
  `InputStream.available()` with a short `delay()` between polls — a blocking `read()` can *not*
  be timeboxed by `withTimeoutOrNull` (cancellation is only observed once the read returns on its
  own), which used to mean a genuinely silent link hung the poll loop until a byte arrived.
  `sendCommand()` also drains any bytes still buffered from a previously timed-out command before
  writing, so a late-arriving response can't be attributed to the wrong request. The CAN monitor's
  capture loop uses the same `available()`-based pattern for its deadline. Connections are
  serialized through a dedicated `connectMutex` (our own fresh RFCOMM connect raises
  `ACL_CONNECTED`, whose receiver used to fire a second, racing `connect()`), and `initELM327()`
  verifies the `ATZ` banner actually says `ELM327` before declaring the link up — a dead or
  foreign SPP device no longer gets a "Connected" badge over a poll loop full of zeros.
- **Check-engine diagnostics:** polls Mode 01 PID 01 (MIL status + DTC count) every ~5s, fetches
  Mode 03 (stored codes) only when there's something to fetch, decodes per SAE J2012 (skipping the
  leading DTC-count byte the CAN response carries, and reassembling ISO-TP multi-frame replies for
  3+ codes), and supports clearing via Mode 04 (`clearDtcs()`), gated behind a confirmation dialog
  since it resets ECU readiness monitors.
- **Diagnostic sweep tools**, all serialized through one `Mutex` so they can't interleave with the
  regular polling loop on the same serial socket:
  - `sweepStandardPidSupport()` — safe, standard Mode 01 PID 00/20/40… bitmask discovery.
  - The original DID sweep — probes manufacturer UDS DIDs across `DEFAULT_SWEEP_HEADERS` (`7E0`
    engine ECU, `7E1` ABS/IMU only — `7E2`-`7E7` are deliberately excluded since probing unconfirmed
    headers is suspected of causing a dash/start-stop hiccup on this bike).
  - `trySecuritySessionProbe()` — **explicitly risky**: sends `10 03` (UDS Extended Diagnostic
    Session) then re-probes the two still-unmapped brake DIDs (`43FE`/`43FF`), always returns to
    Default Session (`10 01`) before finishing. Never auto-invoked; gated behind a UI confirmation
    dialog telling the rider to only run it with the engine off, since unlike every other query in
    this app it changes ECU session state rather than just reading.
  - `startCanMonitor(durationSeconds)` — passive, read-only raw CAN bus capture (`ATH1` + `ATMA`).
    Reads are wrapped in a timeout so a quiet bus (e.g. ignition-on/engine-off) can't hang the
    socket indefinitely; auto-restarts `ATMA` on `BUFFER FULL` until the requested duration elapses;
    throttles UI updates to every 20 frames plus a final flush so multi-minute captures don't thrash
    the StateFlow. Used to reverse-engineer signals that aren't exposed via request/response at all
    (e.g. lean angle, which the IMU appears to broadcast on the bus rather than answer on request).

## 2. Recording & Persistence

- `TelemetryService` (foreground service) owns the actual ride-recording lifecycle: GPS, IMU
  (lean/G-force via phone sensors, with a one-tap calibration to zero out mount tilt), and the OBD
  data loop, all sampled and written to Room at 5Hz regardless of whether OBD is connected.
- Binding (`bindService`, `BIND_AUTO_CREATE`) only runs the service's `onCreate()` — it does **not**
  start recording. Recording only begins via an explicit `startService()` → `onStartCommand()`,
  guarded by a `trackingStarted` flag so a flaky Bluetooth reconnect while already tracking can't
  spin up a second overlapping session.
- **Auto-start & ride continuation:** `ObdAutoStartReceiver` (manifest-registered, works even with
  the app process dead, since ACL-connect broadcasts are exempt from background-broadcast
  restrictions) starts the service when the remembered OBD device connects, if the setting is on.
  `TelemetryService` also registers a dynamic receiver for `ACL_CONNECTED`/`ACL_DISCONNECTED` on
  that device: on disconnect it drops the OBD link and starts a grace-period timer (configurable in
  Settings, default 10 min); reconnecting before it expires resumes into the *same* session,
  otherwise the ride is finalized. The auto-start setting only gates *starting* rides — a ride
  that's already recording (or waiting out its grace window) is reconnected regardless, since
  gating continuation on the same setting used to mean that with auto-start off, a fuel stop
  always ended the ride at grace timeout because nothing ever reconnected. Finalization on grace timeout happens explicitly
  (`finalizeSession()` + `stopForeground` + `stopSelf`), **not** by relying on `onDestroy()` — a
  started-and-bound service isn't destroyed by `stopSelf()` while the UI still holds its binding,
  which used to leave the ride recording phone-sensor records until the user happened to leave the
  app. `onDestroy()` keeps a no-op-if-already-finalized fallback for the explicit Stop path.
- **Ride aggregates:** fuel consumption integrates the rate over the *measured* interval between
  loop iterations (capped at 5s so a stall can't over-count), not a hard-coded 0.2s; the ride's
  `startOdometer` is captured from the first non-zero ODOMETER reading of the ride (reading it at
  connect time always got 0, which would have turned `endOdometer - startOdometer` into the bike's
  absolute odometer the day that DID starts answering); `startOdometer`/`endOdometer` and
  `avgFuelConsumption` (L/100km over GPS distance) are now written onto the finalized session.
- Room DB uses `fallbackToDestructiveMigration()` (dev-only) with a safety net that snapshots the
  raw DB file before a version-bump wipe.

## 3. UI / Navigation

- Bottom nav and the Home screen's Quick Access grid share the same tab order: **Panel → History →
  Analysis → Bike Info → Settings**.
- **Home:** last-ride card, fuel level + estimated range, a service-interval countdown, today's
  ride stats, and Start Tracking/Stop/Backup/Restore controls. The OBD status badge lists paired
  Bluetooth devices directly via `getPairedBluetoothDeviceEntries()` — a plain
  `BluetoothManager.adapter.bondedDevices` read that needs no service binding, so opening the
  device picker from Home can never accidentally trigger service creation or a navigation jump.
- **Panel:** Speed/Gear/RPM/Throttle/Brake gauges, the lean gauge (phone or bike source, tap to
  calibrate), and the check-engine badge.
- **History:** recorded routes as polylines on a Google Map, with distance/duration/average-speed
  overlay.
- **Analysis:** session list (sparkline, stats, rename, delete) plus a detail view with four Vico
  charts — Speed & RPM, Lean Angle, Lateral/Longitudinal G-Force, and Altitude.
- **Bike Info:** identity card + live odometer, an engine-health stat grid, a permanent diagnostics
  card, the live sensor-status table (`PidMapTable`, one row per `CONFIRMED_PID_MAP` entry with a
  green/red/gray status dot), the standard-PID sweep, the manufacturer DID sweep, the risky
  extended-session probe (warm-colored, its own confirmation dialog), and the CAN monitor (duration
  picker, live frame count, share-to-text export).
- **Settings:** auto-start toggle, grace-period stepper, battery-optimization exemption status,
  diagnostic log sharing, language picker.

## 4. Cloud Backup & Restore

- `GoogleDriveManager` builds the Drive client directly from the OAuth access token returned by the
  modern Credential Manager / Authorization API sign-in flow (no legacy `GoogleAccountCredential`).
- Backups checkpoint the Room WAL file before copying the database, so a backup taken right after a
  ride doesn't miss writes still sitting in `-wal`.
- Restore lists backups from the Drive `appDataFolder`, lets the user pick one plus a Replace/Merge
  choice, downloads it, and imports it via raw SQLite reads (`data/BackupRestore.kt`) — Merge skips
  sessions whose `startTime` already exists locally, Replace wipes local data first. Only identity
  and time columns are read strictly; every other column falls back to a default when absent, so a
  backup taken on an older schema restores with degraded fields instead of failing outright.
- Requires a Web-application OAuth client (not the Android client) as the `setServerClientId(...)`
  token audience, and every signing-in account added under Audience → Test users while the consent
  screen is in Testing status — see `README.md` setup steps.

## Verification Notes

- UI/navigation-affecting changes are verified via `adb` (cold launch, screenshot, exercise the
  interaction path) before being reported as done, not just built and unit-tested — a real
  regression (Home unexpectedly jumping straight to Panel) shipped once from binding side effects
  that weren't caught until a screenshot was taken.
- `./gradlew testDebugUnitTest` covers parsing logic (e.g. `testGearParsing`) that's easy to break
  silently — a logging call added inside `parseGear()`'s success branch once corrupted the returned
  gear value because `Log.w` throws in the unmocked JVM test environment and the outer catch-all
  converted that into a wrong return value.
