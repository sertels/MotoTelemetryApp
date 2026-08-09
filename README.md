# Moto Telemetry App - Voge 900DSX (LX900-A)

This project is an Android telemetry application specifically optimized for the **Voge 900DSX** (Factory Model Code: **LX900-A**, also known as **DS900X**).

The application leverages the fact that this motorcycle shares the **BMW F900 architecture** and uses the **4M96001** twin-cylinder engine block (manufactured by Loncin), making it compatible with advanced BMW UDS diagnostic protocols.

**Test vehicle dash cluster firmware:** HW `SS621-L8`, OS `SS621X11-OS-V2.3.2`, HMI `SS621X11-HMI-V4.7`, MCU SW `SS621X11-10`. Every PID/DID in `docs/confirmed_pids.md` was verified against this exact dash - a different HW/OS/HMI/MCU version on your own bike may behave differently, especially for signals not yet mapped.

## 🚀 Key Features

- **Live Engine Data (OBD2):** Real-time monitoring of Speed, RPM, Gear, Throttle Position, and Front/Rear Brake Pressure (via UDS/Enhanced PIDs) using a Bluetooth ELM327 adapter.
- **Physical Analysis (IMU):** Real-time **Lean Angle** and **G-Force** measurement using the phone's internal sensors and the motorcycle's internal IMU, with a one-tap calibration to zero out the phone's mount tilt.
- **Route Tracking (GPS):** Visualization of the riding route on a map using Google Maps integration, with a stats overlay (distance, duration, average speed).
- **Data Logging (Room DB):** Recording all telemetry data (Speed, RPM, Gear, Lean, GPS, etc.) to a local database at 5Hz (5 times per second), independent of whether an OBD2 adapter is connected.
- **Auto-Start & Ride Continuation:** Optionally starts recording automatically the moment the OBD adapter connects (engine on), and keeps a short stop (red light, fuel stop) inside the same ride instead of splitting it into a new session, based on a configurable grace period.
- **Check Engine Diagnostics:** Polls MIL status and stored DTCs (OBD Mode 01/03), surfaces a check-engine badge with the fault codes (each with a plain-language explanation), and supports clearing them (Mode 04) behind a confirmation step.
- **Connection Health:** Detects a dead Bluetooth link (e.g. the adapter losing power when the ignition is switched off) by counting consecutive write failures, instead of leaving the UI stuck showing "OBD Connected" indefinitely.
- **Bike Info Tab:** Live per-sensor readouts (coolant, fuel level/rate, battery voltage, intake/ambient temp, engine load, intake manifold pressure, catalyst temp, engine runtime, ECU odometer, distance since DTC clear, distance with MIL on), a **live sensor map** showing every known CAN header/DID with a green/red/gray dot for whether it answered correctly on the current connection, a diagnostic PID sweep tool, and a raw CAN bus monitor for reverse-engineering unmapped signals.
- **Diagnostic Sweep Tools:** A safe standard-PID support scan (asks the ECU which Mode 01 PIDs it supports), a manufacturer DID sweep across known CAN headers, and an explicitly-gated UDS Extended Diagnostic Session probe (engine-off only, behind a confirmation dialog) for the two still-unmapped brake-related DIDs.
- **Raw CAN Bus Monitor:** Passive, read-only capture of the raw CAN bus (`ATMA`) with selectable duration (8s up to 5 minutes), automatic recovery from ELM327 `BUFFER FULL` conditions on a busy bus, and a shareable text export — used to reverse-engineer signals that aren't exposed through a normal PID/DID request (e.g. lean angle broadcast by the IMU rather than requested from the ECU).
- **Modern Dashboard:** A card-based, cyan-accented dark UI designed with Jetpack Compose, with an adaptive landscape layout and a screen-rotation lock so the display doesn't flip mid-corner.
- **Home Dashboard:** Main menu shows your last ride, current fuel level, and an estimated service-interval countdown, plus quick-access shortcuts to Panel/History/Analysis/Bike Info/Settings.
- **Ride Management:** Rename or delete recorded sessions from the Analysis tab.
- **Analysis Charts:** Per-ride charts for Speed & RPM, Lean Angle, Lateral/Longitudinal G-Force, and Altitude, alongside session stats (max speed, max lean, max G, etc.) and a sparkline per session in the list.
- **Settings Tab:** One place for auto-start/ride-continuation, battery-optimization exemption, diagnostic log sharing, and language, instead of scattered across screens.
- **Multi-language:** Full English/Turkish UI with in-app language switching.
- **Cloud Backup & Restore:** Backup ride data to Google Drive and restore it back, either merging with what's on the phone or replacing it, via a picker listing all available backups by date.
- **Diagnostics Upload:** Push the diagnostic log and any CAN monitor/OBD sweep captures to a visible "MotoTelemetryApp Diagnostics" folder in Drive from Settings, so a real-world test can be reviewed remotely without connecting the phone to a computer.

## 🛠 Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3), Vico charts
- **Architecture:** MVVM (Service → ViewModel → Compose UI, via StateFlow)
- **Database:** Room Database
- **Connectivity:** Bluetooth Classic (RFCOMM), OBD-II Mode 01/03/04, and UDS (ISO 14229) Service 0x22 ReadDataByIdentifier
- **Location:** Google Play Services Fused Location
- **Navigation:** Jetpack Navigation
- **Cloud:** Google Drive API

## 📋 Setup and Usage

1.  **Google Maps API:** Add your own API key to the `com.google.android.geo.API_KEY` field in the `app/src/main/AndroidManifest.xml` file.
2.  **Google Drive Backup/Restore (optional):** Needs a Google Cloud project with two OAuth 2.0 clients in the *same* project:
    - An **Android** client with package name `com.example.mototelemetryapp` and the SHA-1 of your signing certificate (`keytool -list -v -keystore <keystore> -alias <alias>`).
    - A **Web application** client — its ID is what goes into `setServerClientId(...)` in `MainActivity.kt`. Using the Android client's ID there fails with `[28444] Developer console is not set up correctly`; Credential Manager's Google Sign-In requires the Web client ID as the token audience regardless of platform.
    - While the OAuth consent screen is in **Testing** status, every Google account that needs to sign in (including your own) must be added under **Audience → Test users**, or sign-in fails with `403: access_denied`.
    - The app requests both `drive.appdata` (hidden ride-DB backups) and `drive.file` (visible diagnostics uploads, see below) scopes in one grant — add both under the OAuth consent screen's **Data Access** section, or the diagnostics upload silently fails to find/create its Drive folder.
3.  **OBD2 Adapter:** Plug your Bluetooth ELM327 adapter into the motorcycle and pair it with your phone, then connect it once from the Panel's OBD badge dropdown — the app remembers whichever device you pick there, and auto-start (below) only works for a previously-connected device. If you haven't connected one yet, the app falls back to auto-detecting a paired device whose name contains `OBD`, `ELM327`, `ELM`, `VLINK`, `V-LINK`, `ICAR`, `VGATE`, or `OBDLINK`.
4.  **Permissions:** The app will request Bluetooth, Location, and Notification permissions on the first launch.
5.  **Tracking:** Start the data collection loop and background service with the "Start Tracking" button on the main screen, or enable **Auto-start tracking when OBD connects** in Settings to have it start automatically when the adapter powers on.

## 📸 Dashboard Interface

<table>
<tr>
<td><img src="docs/screenshots/home.png" alt="Home screen" width="260"/></td>
<td><img src="docs/screenshots/panel.png" alt="Panel screen" width="260"/></td>
<td><img src="docs/screenshots/bike_info.png" alt="Bike Info screen" width="260"/></td>
</tr>
<tr>
<td align="center">Home</td>
<td align="center">Panel</td>
<td align="center">Bike Info</td>
</tr>
<tr>
<td><img src="docs/screenshots/analysis.png" alt="Analysis screen" width="260"/></td>
<td><img src="docs/screenshots/history.png" alt="History screen" width="260"/></td>
<td><img src="docs/screenshots/settings.png" alt="Settings screen" width="260"/></td>
</tr>
<tr>
<td align="center">Analysis</td>
<td align="center">History (route blurred for privacy)</td>
<td align="center">Settings</td>
</tr>
</table>

- **Main Menu:** Last Ride, Fuel, and Service info cards, Quick Access shortcuts (Panel/History/Analysis/Bike Info/Settings), and Start Tracking/Stop/Backup/Restore controls.
- **Panel:** Displays Speed, Gear, RPM, Throttle, and Brake bars. The lean gauge tilts in real-time based on the selected sensor (phone or bike), with a tap-to-calibrate button and a phone/motorcycle icon toggle. A check-engine badge appears next to the OBD status badge whenever the MIL is on, showing the stored DTCs and a clear-codes action.
- **History:** View recorded routes as Polylines on the map, with a KM/duration/average speed overlay.
- **Analysis:** Session list with per-ride speed sparklines, stats, rename, and delete, plus a detail view charting Speed/RPM, Lean Angle, G-Force, and Altitude over the ride.
- **Bike Info:** Model/engine identity with the live ECU odometer, an engine-health stat grid (coolant, fuel, battery, intake/ambient temp, engine load, MAP, catalyst temp, engine runtime, etc.), a permanent diagnostics card, a live sensor-status table for every known CAN header/DID, a standard-PID and manufacturer-DID sweep tool, a gated UDS extended-session probe, and a raw CAN bus monitor for capturing unmapped signals.
- **Settings:** Auto-start tracking on OBD connect, the ride-continuation grace period, battery-optimization exemption status, diagnostic log sharing, and language.

## 📖 Documentation and Development Process

You can examine the architecture and technical details of the project in the following documents:

- [🚀 Development Walkthrough](docs/walkthrough.md) - Summary of all major subsystems and how they work.
- [📝 Implementation Plan](docs/implementation_plan.md) - Architectural decisions and patterns used across the app.
- [📡 Confirmed PIDs](docs/confirmed_pids.md) - Every OBD-II/UDS request verified to work on this bike, standard vs. manufacturer-specific, for anyone reverse-engineering the same BMW F900 platform.

These same docs are also browsable on the [project wiki](https://github.com/sertels/MotoTelemetryApp/wiki).

## ⚠️ Important Notes

- **Lean Angle:** For the most accurate measurement using phone sensors, it is recommended to mount the phone vertically and securely on the motorcycle.
- **UDS Support:** Data like brake pressure and motorcycle lean angle are BMW/Voge-specific PIDs. The readability of this data may vary depending on the quality of your ELM327 adapter.
- **CAN sweeps and the security-session probe are engine-off tools.** Probing unconfirmed CAN headers or opening a UDS Extended Diagnostic Session changes what the ECU responds to (or its session state) rather than just reading data — only the standard-PID scan and the raw CAN monitor are safe to run with the engine on.

---
*Developed by: Sertel Şekerci*
