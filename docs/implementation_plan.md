# Dual Lean Angle Integration Implementation Plan

This plan outlines the integration of dual lean angle measurement: one from the motorcycle's internal IMU (via OBD2) and one from the phone's internal sensors. Users will be able to choose which one is displayed on the Dashboard.

## User Review Required

> [!IMPORTANT]
> - **OBD2 Header:** The bike's lean angle is typically provided by the ABS module (`7E1`) rather than the Engine ECU (`7E0`). We will attempt to switch headers in the OBD loop to fetch this data.
> - **Display Preference:** We will add a toggle in the UI to switch between "Motorcycle" and "Phone" lean angle displays. Both values will always be recorded for analysis.

## Proposed Changes

### [Data] - Database & Model Updates

#### [MODIFY] [TelemetryRecord.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/TelemetryRecord.kt)
- Rename `leanAngle` to `leanAnglePhone`.
- Add `leanAngleBike: Float`.

#### [MODIFY] [AppDatabase.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/AppDatabase.kt)
- Increment version to 5.

### [OBD2] - Dual Header Querying

#### [MODIFY] [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt)
- Implement header switching logic (`ATSH 7E0` for Engine, `ATSH 7E1` for ABS).
- Add PID `22D10D` query for bike lean angle.
- Handle signed 16-bit parsing for lean angle (degrees).

### [UI] - Dashboard & ViewModel Enhancements

#### [MODIFY] [DashboardViewModel.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/DashboardViewModel.kt)
- Add a `displayPreference` StateFlow (Enum: BIKE, PHONE).
- Add a function to toggle this preference.

#### [MODIFY] [DashboardScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/DashboardScreen.kt)
- Update the lean angle visual to use the preferred data source.
- Add a small toggle button or icon to switch the source.
- (Optional) Show both values in a small "debug" or "secondary" text if requested.

### [Integration] - Service Update

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- Update the recording loop to store both `leanAnglePhone` and `leanAngleBike`.

## Verification Plan

### Manual Verification
- Deploy to device.
- Tilt the phone while stationary and check the "Phone" lean angle.
- Lean the motorcycle (if connected) and check the "Motorcycle" lean angle.
- Toggle between sources on the Dashboard and verify the visual updates accordingly.
- Verify both values are saved in the Database Inspector.
