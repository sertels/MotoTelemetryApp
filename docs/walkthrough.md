# Dual Lean Angle & Header Switching Walkthrough

We have enhanced the telemetry system to capture lean angle from both the motorcycle's internal IMU (via OBD2) and the phone's sensors, allowing real-time switching on the dashboard.

## Changes Made

### 1. Dual Lean Angle Persistence
- **File:** [TelemetryRecord.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/TelemetryRecord.kt)
- **Updates:**
    - Renamed `leanAngle` to `leanAnglePhone`.
    - Added `leanAngleBike` to store data coming directly from the bike's ECU.
- **Database:** Incremented version to 5 in [AppDatabase.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/AppDatabase.kt).

### 2. Advanced OBD2 Communication
- **File:** [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt)
- **Header Switching:** Implemented `ATSH` commands to toggle between the Engine ECU (`7E0`) and the ABS/IMU Module (`7E1`).
- **New PID:** Added `22D10D` query to fetch the motorcycle's internal roll angle.

### 3. Interactive Dashboard
- **File:** [DashboardScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/DashboardScreen.kt)
- **Toggle Feature:**
    - Users can now **tap the lean indicator** to switch between Phone (`TEL`) and Motorcycle (`MOTO`) data sources.
    - Added a `SwapHoriz` icon and source label for clarity.

### 4. Integrated Logic
- **File:** [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- **Recording:** The service now captures and saves both values simultaneously every 200ms.

## Verification Steps

1. **Source Toggle:** On the Dashboard, tap the central lean indicator. You should see the label switch between `TEL` and `MOTO`.
2. **Phone Sensor Test:** Select `TEL`. Tilt your phone and verify the visual tilts accordingly.
3. **Bike Sensor Test:** Select `MOTO`. If connected to the bike, the visual will now follow the bike's internal IMU data.
4. **Data Verification:** Check the **Database Inspector** to see both `leanAnglePhone` and `leanAngleBike` columns being populated.

> [!TIP]
> Using the `MOTO` source is highly recommended for track days as it remains accurate regardless of how the phone is mounted or if it moves in your pocket.

## Summary of All Tracked Data
- **Engine:** RPM, Speed, Gear, Throttle, Brake (Front/Rear).
- **Physics:** Lean Angle (Phone & Bike), G-Force.
- **Geo:** Latitude, Longitude.
- **System:** Timestamp.
