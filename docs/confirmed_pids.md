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

## 📡 Confirmed CAN broadcast frames (passive — no request needed)

Found 2026-08-15 in the first laptop-direct session (`tools/obd_terminal.py` over the adapter's
Bluetooth SPP COM port, captures diffed with `tools/can_diff.py` against known vehicle states).
All 11-bit IDs. Rates are what a clone ELM327 delivers *filtered to one ID* (`ATCRA`); unfiltered
full-bus capture drops most frames past ~150 frames/s, so unfiltered rates read ~5x lower.

| CAN ID | Bytes | Signal | Encoding | Verified how |
|--------|-------|--------|----------|--------------|
| 126    | b0 low nibble | **Gear** | 0=N, 1-6 = gear | Live shift-through: N→1→...→6 each matched the dash exactly; ~50Hz filtered |
| 126    | b0 bit 0x20 | Transient during shifts | Set while working through gears with the bike being rocked (engine off), clear once settled; NOT clutch (tested), NOT sidestand (tested), NOT wheel motion alone (tested). Exact meaning open — mask it off when reading gear. | Elimination tests, engine off |
| 120    | b0-1  | **RPM** | 16-bit BE, raw = RPM (idle read ~1250, fluctuation visible) | Engine idling vs off |
| 120    | b4 bit 0x40 | **Neutral flag** | 0x42 in N, 0x02 in gear | Same shift tests as 126 |
| 3FF    | b0-2  | **Odometer** | 24-bit BE, km (`00 2B 5C` = 11100 km, exact dash match) | Constant across a whole stationary session; final proof = increments after a ride. ~0.2Hz |

TPMS candidates (dash shows tire pressures, so they reach this bus): **3A1** (`A9 C2 56 57 00 00
00 0A`) and **3A2** (`AD C1 AD AD C1 BB 00 00`) — slow (~0.1Hz), payload constant all session, and
3A2's two three-byte groups look like a front/rear pair. Reference for decoding: dash read **front
2.3 / rear 2.7 bar** at capture time (2026-08-15, all captures in that session). No byte maps to
those values under the common encodings (kPa, psi, 25-mbar steps) with one shared formula, so the
decode needs a *changed* pressure to diff against — recheck after a ride (warm tires) or an
inflate/deflate test. Both IDs consistently carry the clone adapter's `<DATA ERROR` checksum
marker; payloads are nonetheless bit-identical across hours, so the data itself is stable.

Negative results from the same session, all with the bike stationary (each tested engine off AND
idling where it could matter):

- **Lean angle is not broadcast while stationary.** Full-bus tilt tests (bike leaned side to side)
  engine-off and engine-running: no byte anywhere on the bus tracks the tilt. **ID 092** — the
  bus's fastest frame (100Hz filtered) — is all-zeros in every state tested; if it's the IMU frame
  it only fills in motion. Next step: a riding capture (laptop in a backpack works).
- **Brake pressure/brake light is not visible stationary** — not as a broadcast (2s
  squeeze/release choreography, diffed) and the `7E1` DIDs stay `NO DATA` even engine-running
  (protocol locked via `0100` first — not the cause). Motion may wake the ABS module; unproven.
- **ID 123** b0-1 carry steady state flags (values shift between ignition-on/engine-run states),
  b6 = 4-bit rolling counter in the high nibble, b7 = checksum over the frame. **ID 191** b7 is a
  rolling counter (looked like a tilt hit in a naive variance diff — it isn't).
- The engine-off bus inventory (ignition on): 092/12B at ~20Hz observed unfiltered, 120/123/125/
  126/12F at ~10Hz, 191/192/220 at ~4Hz, 240/241/270/2A8/2D2 at ~2Hz, 320/341/3A1/3A2/3FD/3FF
  sparse. (Unfiltered = undercounted; see note above.)

## ❌ Not working

All seven rows below were listed as confirmed in an earlier pass of this doc. Cross-checked
2026-08-09 against every real (non-simulated) ride ever recorded in the app's own database (55k+
records going back to 2026-08-01, excluding the day's 3 known-simulated test rides) — every one of
these columns is **exactly zero in every single real record**, never once a nonzero value, which
the diagnostic log's failures for the same requests corroborate.

| Header | PID/DID | Signal                | Response |
|--------|---------|------------------------|----------|
| 7E0    | 2243F7  | Gear                   | `7F 22 31`, even with a UDS Extended Diagnostic Session actively granted (`50 03`) |
| 7E0    | 222503  | ECU odometer           | `7F 22 31`, same as Gear |
| 7E1    | 222B05  | Front brake pressure   | `NO DATA` |
| 7E1    | 222B06  | Rear brake pressure    | `NO DATA` |
| 7E1    | 22D10D  | Lean angle (bike IMU)  | `NO DATA` |
| 7E0    | 012F    | Fuel level             | `NO DATA` |
| 7E0    | 015E    | Fuel rate              | `NO DATA` |
| 7E0    | 43FE    | Unmapped                | `7F 22 31`, even with the session granted |
| 7E0    | 43FF    | Unmapped                | `7F 22 31`, same as `43FE` |

Checking the recorded DB instead of just grepping the log for failures is what caught fuel
level/rate and the two brake DIDs — a live spot-check earlier the same day had wrongly reported
"yes, fuel level and rate work" going only off this doc's stale confirmed table. The extended-session
hypothesis for `2243F7`/`222503`/`43FE`/`43FF` was separately disproved the same day (session
granted, `50 03`, all four still failed identically) — root cause for any row above is still open.

Re-verified 2026-08-15 from the laptop, stationary: `2243F7`/`222503` still `7F 22 31` and all
three `7E1` DIDs still `NO DATA`, in every state available standing still (ignition-only AND
engine idling, protocol pre-locked via `0100`). Whatever these DIDs need, it isn't just "engine
running" — motion is the last untested variable. Gear and odometer no longer matter here anyway:
both are now read passively from broadcasts (see the broadcast section above).

Two related open leads, not request/response PIDs so they don't fit the table above:

- **Lean angle broadcast on the raw CAN bus** — `22D10D` never answers on request (see above), and
  the 2026-08-15 stationary tilt tests (see the broadcast section) proved it is not broadcast while
  standing still either, engine off or idling. If it's on this bus at all it only flows in motion —
  a riding capture is the remaining test. (Gear, which was in this same bullet list until
  2026-08-15, IS broadcast and is now solved — ID `126`, see the broadcast section; the reason the
  2026-08-09 shift test missed it is that unfiltered clone captures drop ~80% of frames.)
- **A completely different channel for fuel/gear/odometer, unexplored so far** — the bike's own
  dash shows all three despite the OBD/UDS requests above never working, so it's getting them some
  other way. [MOTO-HUB](https://github.com/vincenzobpt/MOTO-HUB) (a screen-mirroring app for
  Voge/CFMOTO/Zontes dashboards, unrelated project) confirms these dashboards have a **T-Box
  (telematics unit) reachable over Wi-Fi Direct**, separate from the OBD port - not something this
  app has ever tried talking to. Whether the dash also exposes anything over a direct Bluetooth
  link (rather than only Wi-Fi Direct to the T-Box) is unknown. Nothing implemented here yet - just
  a lead for whoever picks this up next.

## How this list was built

Combination of: SAE J1979 standard PID scanning (`sweepStandardPidSupport()` in the app), manual
UDS DID sweeps across the two confirmed-responsive headers (`7E0`, `7E1`), and cross-referencing
another OBD app's raw sensor dumps against real dashboard readings taken at the same time. Since
2026-08-15 there's also a phone-free route: `tools/obd_terminal.py` drives the adapter directly
from a laptop over its Bluetooth SPP COM port (probe / raw commands / a change-only `watch` /
CAN-ID histogram / ATMA captures, full-bus or `ATCRA`-filtered to one ID), and
`tools/can_diff.py` diffs captures taken in different known vehicle states — that pair is what
found every entry in the broadcast section. PIDs
that returned data but couldn't be correlated to a real, verifiable value on the bike are
deliberately left out of this list rather than included as a guess. As of 2026-08-09, entries are
also periodically cross-checked against the app's own recorded ride database rather than trusted
from the original sweep alone - see Not working above for what that caught.
