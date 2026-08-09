# Confirmed PIDs — Voge 900DSX (LX900-A / BMW F900 platform, Loncin 4M96001 engine)

Every request in this table has been verified to return a real, correctly-decoded value on this
bike (not just "no error") via live on-bike testing with a Bluetooth ELM327 adapter. This should
generalize to other bikes on the same BMW F900 platform (Voge/Loncin 4M96001), though exact values
may vary by model year/ECU revision — verify against a real ride before trusting any of it.

This is the same list the app polls (`BluetoothOBDManager.CONFIRMED_PID_MAP`), and the app's own
Bike Info tab shows a **live** version of it — a green/red/gray dot per row for whether the last
request on the current connection actually matched what was expected, not just a static claim.

**Headers used:** `7E0` = engine ECU (Bosch ME17.8.10), `7E1` = ABS/IMU module.

**Reference vehicle** (via Mode 09, cross-checked with another OBD app — PID `0902`/`0904`): VIN
`LLCVPX1A8RA150987`, calibration ID `8814LX28502BLXMT`. Worth checking against when
cross-referencing another BMW F900-platform bike's CAN/DID data (e.g. the community CAN-ID
spreadsheet) — different calibration revisions can shift layouts even on the same platform.

## ✅ Confirmed working

### Standard OBD-II (SAE J1979, Mode 01/03)

These follow the standard and should work on most OBD-II-compliant vehicles, not just this one.

| Header | PID    | Signal                        |
|--------|--------|--------------------------------|
| 7E0    | 010C   | RPM                            |
| 7E0    | 010D   | Speed                          |
| 7E0    | 0111   | Throttle position              |
| 7E0    | 0105   | Coolant temp                   |
| 7E0    | 0131   | Distance since DTC clear (**not** the real lifetime odometer — resets on Mode 04 clear) |
| 7E0    | 0142   | Battery / control module voltage |
| 7E0    | 010F   | Intake air temp                |
| 7E0    | 0104   | Engine load                    |
| 7E0    | 0146   | Ambient air temp                |
| 7E0    | 010B   | Intake manifold pressure (MAP) |
| 7E0    | 010E   | Timing advance                 |
| 7E0    | 011F   | Engine run time                |
| 7E0    | 0121   | Distance with MIL on           |
| 7E0    | 013C   | Catalyst temp, Bank 1 Sensor 1 |
| 7E0    | 013D   | Catalyst temp, Bank 2 Sensor 1 |
| 7E0    | 0106   | Short-term fuel trim, Bank 1   |
| 7E0    | 0107   | Long-term fuel trim, Bank 1    |
| 7E0    | 0108   | Short-term fuel trim, Bank 2   |
| 7E0    | 0109   | Long-term fuel trim, Bank 2    |
| 7E0    | 0114   | O2 sensor voltage/trim, Bank 1 Sensor 1 |
| 7E0    | 0118   | O2 sensor voltage/trim, Bank 2 Sensor 1 |
| 7E0    | 0143   | Absolute load value            |
| 7E0    | 0144   | Commanded equivalence ratio    |
| 7E0    | 0145   | Relative throttle position     |
| 7E0    | 0147   | Throttle position B            |
| 7E0    | 0149   | Accelerator pedal position D   |
| 7E0    | 014A   | Accelerator pedal position E   |
| 7E0    | 014C   | Commanded throttle actuator    |
| 7E0    | 0101   | MIL status / stored DTC count  |
| 7E0    | 03     | Stored DTCs (Mode 03, decoded per SAE J2012) |

Manufacturer UDS DIDs (`7E1` header) are covered in Not working below — as of 2026-08-09 none of
them have ever returned real data on this bike; see that section for how this was checked.

## ❌ Not working

- **`2243F7` (Gear), `222503` (ECU odometer), `222B05` (front brake), `222B06` (rear brake),
  `22D10D` (bike-IMU lean angle) — all header `7E0`/`7E1` UDS DIDs, `012F` (fuel level) and `015E`
  (fuel rate) — standard PIDs.** All seven were listed as confirmed in an earlier pass of this doc.
  Cross-checked 2026-08-09 against every real (non-simulated) ride ever recorded in the app's own
  database (55k+ records going back to 2026-08-01, `sessionId NOT IN` the day's 3 known-simulated
  test rides) — every one of these seven columns is **exactly zero in every single real record**,
  never once a nonzero value, which the diagnostic log's `NO DATA`/`7F 22 31` failures for the same
  requests corroborate. Checking the recorded DB instead of just grepping the log for failures is
  what caught fuel level/rate and the two brake DIDs - a live spot-check earlier the same day had
  wrongly reported "yes, fuel level and rate work" going only off this doc's stale confirmed table.
  Gear/odometer's extended-session hypothesis was separately disproved the same day (session
  granted, `50 03`, both DIDs still `7F 22 31`) - root cause for any of these seven is still open.
- **Lean angle broadcast on the raw CAN bus** — `22D10D` above never answers on request either (see
  above), but there's reason to believe the IMU also broadcasts lean angle passively on the bus
  without being asked. The app's raw CAN monitor (Bike Info) exists to capture and manually
  correlate this against known bike tilt, for anyone who wants to find the broadcasting CAN ID.
- **Gear signal on the raw CAN bus** — a 1-2-N-1 shift test against a passive CAN capture
  (2026-08-09) found no CAN ID with a clean 3-transition pattern matching the three shifts; the
  earlier `12B` lead from a prior session's stall-event capture didn't repeat here either. Still
  unconfirmed.

## How this list was built

Combination of: SAE J1979 standard PID scanning (`sweepStandardPidSupport()` in the app), manual
UDS DID sweeps across the two confirmed-responsive headers (`7E0`, `7E1`), and cross-referencing
another OBD app's raw sensor dumps against real dashboard readings taken at the same time. PIDs
that returned data but couldn't be correlated to a real, verifiable value on the bike are
deliberately left out of this list rather than included as a guess. As of 2026-08-09, entries are
also periodically cross-checked against the app's own recorded ride database rather than trusted
from the original sweep alone - see Not working above for what that caught.
