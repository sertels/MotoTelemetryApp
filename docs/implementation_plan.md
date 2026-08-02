# Architecture & Design Decisions

The patterns and tradeoffs behind how this app is built, kept current as the app changes — not a
one-time plan for a single round of work. See `docs/walkthrough.md` for what each subsystem does;
this doc is about *why* it's built that way.

## Service → ViewModel → UI data flow

`TelemetryService` (a foreground service) owns every live data source (OBD, GPS, IMU) and exposes
each as a `MutableStateFlow`/`asStateFlow()` pair. `DashboardViewModel` collects those into its own
mirrored `StateFlow`s on `onServiceConnected`, and **cancels the collection without resetting the
value** on disconnect/unbind — so the UI doesn't flicker to a blank state while a ride keeps
recording in the background, and simply re-collects (picking up live values again) on the next
connect. This pattern is repeated for every new live signal added to the app (e.g. `pidStatus`,
`canMonitorFrames`) rather than inventing a new plumbing style per feature.

`bindService(BIND_AUTO_CREATE)` only runs the service's `onCreate()` — it does not start
recording. That distinction mattered: a fix that bound the service just to list paired Bluetooth
devices from the Home screen accidentally created the service, which shared an `isBound` flag with
the Home-vs-Panel view router and caused Home to jump straight to Panel. The fix was to decouple
device listing from binding entirely — `getPairedBluetoothDeviceEntries()` reads
`BluetoothManager.adapter.bondedDevices` directly with no service involvement — rather than trying
to special-case the router. General lesson: don't let two unrelated concerns share one boolean flag
just because they happen to change at similar times.

## OBD/UDS polling: two tiers, not one

Every new PID added to the app was initially tempting to just add to the existing 100ms polling
loop, but each additional `sendCommand()` costs a full serial round trip on top of the existing
~11 requests already in that loop. Signals that actually need to be responsive at gauge refresh
rate (RPM, Speed, Gear, Throttle, Brake, Lean, Coolant) stay in the hot `pollOnce()` loop; everything
else (odometer, fuel, battery, temps, trims, etc. — over 20 PIDs) moved to `pollSlowPids()`, gated
to the same ~5s cadence as DTC polling, with results *merged* into the existing data map rather than
replacing it so slow-tier values don't blink to zero between refreshes. This is the same
hot/cold-path split you'd apply to any polling system with a shared, rate-limited transport — don't
let low-priority reads starve the high-priority ones sharing the same channel.

## Confirmed vs. claimed sensor support

Early on, the Bike Info "sensor map" was a static list — command exists in code, therefore "listed
as supported" — which drifted from reality (odometer and fuel PIDs were listed as working while
silently returning garbage). The fix wasn't better parsing, it was **admitting live status
in the UI**: every parse function now reports success/failure via `markPidStatus()`
(`pidStatus: StateFlow<Map<String, Boolean>>`), and the sensor map renders that live instead of a
hardcoded claim. Generalizes to any system with a "here's what we support" list: if the list can
silently drift from what's actually working, make the list observe reality instead of declaring it.

## Risk-tiered diagnostic tooling

Not every diagnostic query is equally safe, and the UI treats them differently on purpose:

- **Pure reads** (standard PID scan, manufacturer DID sweep, CAN monitor) — safe to run anytime,
  default-colored UI, no confirmation gate.
- **UDS Extended Diagnostic Session** (`10 03`) — changes ECU session state, not just a read. Kept
  as its own explicitly-risky action: warm/red-tinted UI, a confirmation dialog telling the rider to
  only run it with the engine off, and the implementation always returns to Default Session
  (`10 01`) before finishing regardless of outcome. Never auto-invoked by anything else in the app.
- **Unconfirmed CAN headers** (`7E2`-`7E7`) are excluded from the default sweep entirely — probing
  them unsolicited is suspected of having caused a dash/start-stop hiccup during testing. Opting
  into them would need to be a deliberate, separate action, not a side effect of the normal sweep.

The general rule: anything that could change vehicle ECU state (vs. just reading it) needs visibly
different UI treatment and an explicit confirmation step, scaled to how risky the specific action
is — a plain read gets none, a session-state change gets a dialog, and truly unknown territory
(unconfirmed headers) is excluded by default rather than gated.

## Dead-link detection

`sendCommand()`/`readResponse()` deliberately swallow `IOException` so that one unsupported PID
doesn't kill the whole polling loop — an expected, common case (most PIDs are unsupported on any
given ECU). But that same swallowing hid genuine socket death (adapter losing power when the
ignition switches off) from ever reaching `startDataLoop()`'s error handling, so the UI stayed on
"OBD Connected" indefinitely after a real disconnect. The fix distinguishes the two cases:
`consecutiveWriteFailures` only increments on *write* failures, since — unlike a read timeout, which
legitimately happens for an unconfirmed PID — a write failure only happens when the RFCOMM link
itself is actually broken. Crossing a small threshold (8, roughly one full poll cycle) triggers a
real `disconnect()`. General pattern: when swallowing an expected error class, make sure a
different, unambiguous error signal still gets through.

## Room DB versioning

Uses `fallbackToDestructiveMigration()` (a dev-only choice, appropriate for a solo hobby-ride
logger, not something to carry into a shared-user product) paired with a safety net that snapshots
the raw DB file before a version-bump wipe. New columns bump `DB_VERSION` and use
`getColumnIndex` (returning `-1` on a missing column) rather than `getColumnIndexOrThrow`, so
restoring an older backup after a schema change degrades gracefully instead of crashing.

## Compose layout gotchas worth remembering

- **`IntrinsicSize.Min`:** a `Text`'s `minIntrinsicWidth` assumes wrapping is fine unless
  `maxLines = 1, softWrap = false` is set on *every* `Text` in the intrinsically-sized subtree
  (labels **and** values) — the narrowest-width `Text` determines the forced column width and drags
  unguarded siblings down with it.
- **`Arrangement.SpaceBetween`** degenerates to zero gaps when a `Row` is sized via
  `IntrinsicSize.Min` (there's no slack space left to distribute). Use `Arrangement.spacedBy(dp)`
  instead, or parameterize the arrangement so portrait/landscape can each get correct behavior.

## Verification discipline

UI- and navigation-affecting changes are verified via `adb` (cold launch, tap through the actual
interaction path, screenshot) before being reported as done — not just built and unit-tested. This
was learned the hard way: a fix for a dead OBD badge on Home was reported as working after only a
build+test pass, and it turned out to have introduced a real navigation regression (Home jumping
straight to Panel) that a screenshot would have caught immediately.
