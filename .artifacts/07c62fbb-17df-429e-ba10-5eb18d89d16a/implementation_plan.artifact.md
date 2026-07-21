# Localization (English/Turkish) Implementation Plan

This plan outlines the steps to add multi-language support (English and Turkish) to the Moto Telemetry application, including a UI toggle for dynamic switching.

## Proposed Changes

### [Resources] - Localization Files

#### [MODIFY] [strings.xml](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/res/values/strings.xml)
Move all hardcoded strings in the code to this file (English as default).

#### [NEW] [strings.xml (tr)](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/res/values-tr/strings.xml)
Create a Turkish translation of all strings.

### [UI] - Language Selection

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Add a language toggle (English/Turkish) to the `MainScreen`.
- Use `AppCompatDelegate.setApplicationLocales` (or a custom context wrapper if needed for Compose) to switch the language dynamically.

#### [MODIFY] [DashboardScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/DashboardScreen.kt)
- Replace hardcoded strings like "HIZ", "VİTES", "DEVİR" with `stringResource(R.string...)`.

### [Core] - Codebase Cleanup

#### [MODIFY] [BluetoothOBDManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/BluetoothOBDManager.kt) & [TelemetryService.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/TelemetryService.kt)
- Move Log and Toast messages to string resources where applicable (Logs usually stay in English for debugging, but user-facing messages must be localized).

## Verification Plan

### Manual Verification
- Deploy to device.
- Toggle between English and Turkish on the Main Screen.
- Verify that the Dashboard, History, and Toast messages update to the selected language.
- Ensure the selected language persists across app restarts (if using a persistence mechanism).
