# Minimum SDK Version Upgrade (API 31) Walkthrough

We have upgraded the minimum supported Android version to **API 31 (Android 12)**. This allows the application to use modern Bluetooth and background service features as a baseline, simplifying the codebase and improving stability for Voge 900DSX telemetry tracking.

## Changes Made

### 1. Build Configuration
- **File:** [build.gradle.kts (app)](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/build.gradle.kts)
- **Action:** Updated `minSdk` from 26 to **31**.

### 2. Codebase Cleanup
- **MainActivity.kt:** Removed redundant `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` checks for Bluetooth permissions. These are now requested unconditionally since the app starts at API 31.
- **Theme.kt:** Simplified the dynamic color scheme logic by removing the SDK version check, as Material You dynamic colors are natively supported in all versions the app now targets.

### 3. Verification
- **Gradle Sync:** Successfully synchronized the project with the new SDK configuration.
- **Permission Flow:** Verified that the modern Bluetooth Scan and Connect permissions are correctly integrated into the main permission request list.

## Benefits for Moto Telemetry
- **Reliable Bluetooth:** Native support for Android 12 Bluetooth permissions ensures seamless connection to ELM327 adapters.
- **Service Stability:** Foreground services are managed more strictly and reliably starting from API 31, reducing the risk of tracking interruptions during a ride.
- **Modern UI:** Full support for Dynamic Colors (Material You) on all supported devices.

> [!NOTE]
> The app will now only install on devices running Android 12 or newer.
