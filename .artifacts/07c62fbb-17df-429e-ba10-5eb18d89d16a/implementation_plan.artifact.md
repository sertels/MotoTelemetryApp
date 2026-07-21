# Fix App Crashing on Startup Plan

This plan aims to resolve the "open and close" issue reported by the user, likely caused by Android 14+ foreground service requirements or database initialization.

## Proposed Changes

### [Core] - Foreground Service Compatibility

#### [MODIFY] [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- Update `startForeground` to include the required service types for Android 14+ (`location` and `connectedDevice`).
- Ensure all required permissions are checked before calling `startForeground`.

### [Database] - Schema Integrity

#### [MODIFY] [AppDatabase.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/AppDatabase.kt)
- Verify `fallbackToDestructiveMigration` is correctly applied to prevent crashes after schema updates.

### [UI] - Startup Stability

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Wrap potential crashing initializers (like `Identity` or `CredentialManager`) in safe checks.
- Add an `UncaughtExceptionHandler` log to help identify the exact crash cause in the future.

## Verification Plan

### Manual Verification
- Deploy to a physical device or emulator running Android 14+.
- Verify the app stays open and the Main Screen is visible.
- Click "Start Tracking" and ensure the foreground notification appears without crashing.
