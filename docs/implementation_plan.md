# Increase Minimum SDK Version Plan

This plan outlines the steps to increase the minimum supported Android version to API 31 (Android 12) and clean up the codebase accordingly.

## Proposed Changes

### [Build] - Gradle Update

#### [MODIFY] [build.gradle.kts](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/build.gradle.kts)
- Set `minSdk = 31`.
- Verify and update any dependencies that might benefit from a higher minSdk.

### [Code] - SDK Check Cleanup

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Remove `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)` checks (API 31 is now the floor).
- Keep `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)` for POST_NOTIFICATIONS permission if necessary, as API 33 is higher than 31.

#### [MODIFY] [Theme.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/theme/Theme.kt)
- Simplify dynamic color check.

## Verification Plan

### Automated Tests
- Run Gradle sync and a full project build to ensure compatibility.

### Manual Verification
- Deploy to an Android 12+ device (or emulator).
- Verify that permissions are still requested correctly.
- Ensure the Foreground Service starts without issues.
