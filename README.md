# Moto Telemetry App - Voge 900DSX (LX900-A)

This project is an Android telemetry application specifically optimized for the **Voge 900DSX** (Factory Model Code: **LX900-A**, also known as **DS900X**). 

The application leverages the fact that this motorcycle shares the **BMW F900 architecture** and uses the **4M96001** twin-cylinder engine block (manufactured by Loncin), making it compatible with advanced BMW UDS diagnostic protocols.

## 🚀 Key Features

- **Live Engine Data (OBD2):** Real-time monitoring of Speed, RPM, Throttle Position, and Front/Rear Brake Pressure (via UDS/Enhanced PIDs) using a Bluetooth ELM327 adapter.
- **Physical Analysis (IMU):** Real-time **Lean Angle** and **G-Force** measurement using the phone's internal sensors and the motorcycle's internal IMU, with a one-tap calibration to zero out the phone's mount tilt.
- **Route Tracking (GPS):** Visualization of the riding route on a map using Google Maps integration, with a stats overlay (distance, duration, average speed).
- **Data Logging (Room DB):** Recording all telemetry data (Speed, RPM, Lean, GPS, etc.) to a local database at 5Hz (5 times per second), independent of whether an OBD2 adapter is connected.
- **Auto-Start & Ride Continuation:** Optionally starts recording automatically the moment the OBD adapter connects (engine on), and keeps a short stop (red light, fuel stop) inside the same ride instead of splitting it into a new session, based on a configurable grace period.
- **Check Engine Diagnostics:** Polls MIL status and stored DTCs (OBD Mode 01/03), surfaces a check-engine badge with the fault codes, and supports clearing them (Mode 04) behind a confirmation step.
- **Bike Info Tab:** Live engine-health readouts (coolant, fuel level/rate, ECU odometer), a permanent diagnostics card, and a reference table of every confirmed CAN header/DID → signal mapping, with a shortcut into the diagnostic PID sweep tool.
- **Modern Dashboard:** A card-based, cyan-accented dark UI designed with Jetpack Compose, with an adaptive landscape layout and a screen-rotation lock so the display doesn't flip mid-corner.
- **Home Dashboard:** Main menu shows your last ride, current fuel level, and an estimated service-interval countdown, plus quick-access shortcuts to Panel/History/Bike Info/Analysis/Settings.
- **Ride Management:** Rename or delete recorded sessions from the Analysis tab.
- **Settings Tab:** One place for auto-start/ride-continuation, battery-optimization exemption, and language, instead of scattered across screens.
- **Multi-language:** Full English/Turkish UI with in-app language switching.
- **Cloud Backup & Restore:** Backup ride data to Google Drive and restore it back, either merging with what's on the phone or replacing it, via a picker listing all available backups by date.

## 🛠 Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Architecture:** MVVM & Clean Architecture
- **Database:** Room Database
- **Connectivity:** Bluetooth Classic (RFCOMM) & UDS Protocol
- **Location:** Google Play Services Fused Location
- **Navigation:** Jetpack Navigation
- **Cloud:** Google Drive API

## 📋 Setup and Usage

1.  **Google Maps API:** Add your own API key to the `com.google.android.geo.API_KEY` field in the `app/src/main/AndroidManifest.xml` file.
2.  **Google Drive Backup/Restore (optional):** Needs a Google Cloud project with two OAuth 2.0 clients in the *same* project:
    - An **Android** client with package name `com.example.mototelemetryapp` and the SHA-1 of your signing certificate (`keytool -list -v -keystore <keystore> -alias <alias>`).
    - A **Web application** client — its ID is what goes into `setServerClientId(...)` in `MainActivity.kt`. Using the Android client's ID there fails with `[28444] Developer console is not set up correctly`; Credential Manager's Google Sign-In requires the Web client ID as the token audience regardless of platform.
    - While the OAuth consent screen is in **Testing** status, every Google account that needs to sign in (including your own) must be added under **Audience → Test users**, or sign-in fails with `403: access_denied`.
3.  **OBD2 Adapter:** Plug your Bluetooth ELM327 adapter into the motorcycle and pair it with your phone, then connect it once from the Panel's OBD badge dropdown — the app remembers whichever device you pick there, and auto-start (below) only works for a previously-connected device. If you haven't connected one yet, the app falls back to auto-detecting a paired device whose name contains `OBD`, `ELM327`, `ELM`, `VLINK`, `ICAR`, `VGATE`, or `OBDLINK`.
4.  **Permissions:** The app will request Bluetooth, Location, and Notification permissions on the first launch.
5.  **Tracking:** Start the data collection loop and background service with the "Start Tracking" button on the main screen, or enable **Auto-start tracking when OBD connects** in Settings to have it start automatically when the adapter powers on.

## 📸 Dashboard Interface

- **Main Menu:** Last Ride, Fuel, and Service info cards, Quick Access shortcuts (Panel/History/Bike Info/Analysis/Settings), and Start Tracking/Stop/Backup/Restore controls.
- **Panel:** Displays Speed, RPM, Gear, Throttle, and Brake bars. The lean gauge tilts in real-time based on the selected sensor (phone or bike), with a tap-to-calibrate button and a phone/motorcycle icon toggle. A check-engine badge appears next to the OBD status badge whenever the MIL is on, showing the stored DTCs and a clear-codes action.
- **History:** View recorded routes as Polylines on the map, with a KM/duration/average speed overlay.
- **Bike Info:** Model/engine identity with the live ECU odometer, an engine-health grid (coolant, fuel level, fuel rate), a permanent diagnostics card, a reference table of confirmed CAN header/DID → signal mappings, and a shortcut into the diagnostic PID sweep tool.
- **Analysis:** Session list with per-ride speed sparklines, stats, rename, and delete, plus a detail view charting Speed and RPM over the ride.
- **Settings:** Auto-start tracking on OBD connect, the ride-continuation grace period, battery-optimization exemption status, and language.

## 📖 Documentation and Development Process

You can examine the step-by-step development and technical details of the project in the following documents:

- [🚀 Development Walkthrough](docs/walkthrough.md) - Summary of all operations and features implemented.
- [📝 Implementation Plan](docs/implementation_plan.md) - Architectural decisions and planned stages.

## ⚠️ Important Notes

- **Lean Angle:** For the most accurate measurement using phone sensors, it is recommended to mount the phone vertically and securely on the motorcycle.
- **UDS Support:** Data like brake pressure and motorcycle lean angle are BMW/Voge specific PIDs. The readability of this data may vary depending on the quality of your ELM327 adapter.

---
*Developed by: Sertel Şekerci*
