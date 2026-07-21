# Gear Integration & Google Drive Backup Plan

This plan outlines the integration of Gear Position data from OBD2 and a backup system using Google Drive.

## User Review Required

> [!IMPORTANT]
> - **Gear PID:** We will use PID `2243F7` (BMW/Voge UDS). If the adapter doesn't support it, the gear will default to 0.
> - **Google Drive:** This will require setting up a Google Cloud Project and adding the `google-services.json` file. We will implement this in a separate step after the Gear integration.

## Proposed Changes

### [Data] - Database & Model Updates

#### [MODIFY] [TelemetryRecord.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/TelemetryRecord.kt)
- Add `gear: Int` field.

#### [MODIFY] [AppDatabase.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/AppDatabase.kt)
- Increment version to 4.

### [OBD2] - Gear Querying

#### [MODIFY] [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt)
- Add command `2243F7` to the data loop.
- Add `parseGear` logic (Standard BMW mapping).

### [UI] - Dashboard Enhancement

#### [MODIFY] [DashboardScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/DashboardScreen.kt)
- Add a large digital Gear indicator in the center or near the Speedometer.

### [Integration] - Service Update

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- Include the `gear` value in the `TelemetryRecord` created in the loop.

## Verification Plan

### Manual Verification
- Deploy to device.
- Shift through gears on the motorcycle and verify the Dashboard shows the correct gear (1-6, 0 for N).
- Use Database Inspector to verify the `gear` column is correctly populated in the `telemetry_records` table.
