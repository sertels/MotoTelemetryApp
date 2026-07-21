# Advanced Analytics, Sessions & Dual Distance Tracking Plan

This plan outlines the integration of ride session tracking, Vico-based charts, and dual distance measurement (Motorcycle Odometer vs. GPS Calculation).

## User Review Required

> [!IMPORTANT]
> - **Dual Distance:** We will track distance in two ways:
>   1. **Bike Data:** Using the motorcycle's internal odometer (PID `222503`).
>   2. **GPS Data:** Calculating the cumulative distance between GPS coordinates in real-time.
> - **Session Management:** Users can rename sessions. Start/End timestamps and durations will be automatically recorded.

## Proposed Changes

### [Data] - Rich Session Model

#### [MODIFY] [Session.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/Session.kt)
Update entity with:
- `totalDistanceBikeKm: Float`
- `totalDistanceGpsKm: Float`
- `maxCoolantTemp: Int`
- `startOdometer: Long`
- `endOdometer: Long`

### [Core] - Tracking Logic

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- **Odometer:** Capture the bike's odometer reading at the start and end of each session.
- **GPS Distance:** Use `Location.distanceTo()` to calculate the distance between the current and previous GPS points and accumulate it.
- **Aggregates:** Maintain running max values for Speed, Lean Angle, and Coolant Temp.

#### [MODIFY] [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt)
- Add PIDs: `222503` (Odometer) and `0105` (Coolant Temp).
- Implement parsers for these values.

### [UI] - Analytics & Interactive Charts

#### [NEW] [AnalysisScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/AnalysisScreen.kt)
- **Session List:** Show start/end times and total distance.
- **Edit Name:** Allow users to rename the ride.
- **Charts (Vico):**
    - Combined Speed/RPM chart.
    - Lean Angle (Left vs Right) distribution.
    - Fuel Rate and Coolant Temp trend.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Add "Analysis" (İstatistikler) to the bottom navigation bar.

## Verification Plan

### Manual Verification
- Start a tracking session and ride (or simulate movement).
- Verify that both GPS distance and Bike distance are being updated.
- Stop the session and verify that all aggregates (Max Speed, Max Lean) are saved.
- Navigate to the Analysis screen and ensure charts render with the recorded data.
