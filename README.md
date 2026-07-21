# Moto Telemetry App - Voge 900DSX (LX900-A)

This project is an Android telemetry application specifically optimized for the **Voge 900DSX** (Factory Model Code: **LX900-A**, also known as **DS900X**). 

The application leverages the fact that this motorcycle shares the **BMW F900 architecture** and uses the **4M96001** twin-cylinder engine block (manufactured by Loncin), making it compatible with advanced BMW UDS diagnostic protocols.

## 🚀 Key Features

- **Live Engine Data (OBD2):** Real-time monitoring of Speed, RPM, Throttle Position, and Front/Rear Brake Pressure (via UDS/Enhanced PIDs) using a Bluetooth ELM327 adapter.
- **Physical Analysis (IMU):** Real-time **Lean Angle** and **G-Force** measurement using the phone's internal sensors and the motorcycle's internal IMU.
- **Route Tracking (GPS):** Visualization of the riding route on a map using Google Maps integration.
- **Data Logging (Room DB):** Recording all telemetry data (Speed, RPM, Lean, GPS, etc.) to a local database at 5Hz (5 times per second).
- **Modern Dashboard:** A sporty dashboard designed with Jetpack Compose to visualize live data.
- **Cloud Backup:** Secure backup and restore of ride data using Google Drive API.

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
2.  **OBD2 Adapter:** Plug your Bluetooth ELM327 adapter into the motorcycle and pair it with your phone (ensure the pairing name contains "OBDII").
3.  **Permissions:** The app will request Bluetooth, Location, and Notification permissions on the first launch.
4.  **Tracking:** You can start the data collection loop and background service using the "Start Tracking" button on the main screen.

## 📸 Dashboard Interface

- **Panel:** Displays Speed, RPM, Gear, Throttle, and Brake bars. The motorcycle silhouette tilts in real-time based on the selected sensor data.
- **History:** View recorded routes as Polylines on the map.

## 📖 Documentation and Development Process

You can examine the step-by-step development and technical details of the project in the following documents:

- [🚀 Development Walkthrough](docs/walkthrough.md) - Summary of all operations and features implemented.
- [📝 Implementation Plan](docs/implementation_plan.md) - Architectural decisions and planned stages.

## ⚠️ Important Notes

- **Lean Angle:** For the most accurate measurement using phone sensors, it is recommended to mount the phone vertically and securely on the motorcycle.
- **UDS Support:** Data like brake pressure and motorcycle lean angle are BMW/Voge specific PIDs. The readability of this data may vary depending on the quality of your ELM327 adapter.

---
*Developed by: Sertel Şekerci*
