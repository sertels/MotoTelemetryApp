# Implementation Plan - Fix Room AppDatabase_Impl Not Found

The application crashes at runtime because the Room database implementation (`AppDatabase_Impl`) is not generated. This is due to using `annotationProcessor` in a Kotlin-only module with Kotlin 2.4.10 and AGP 9.3.0. In this environment, `kapt` or `ksp` must be used for annotation processing.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/gradle/libs.versions.toml)
- Add `ksp` version: `2.4.10-1.0.0` (matching Kotlin version).
- Add `ksp` plugin definition.

#### [MODIFY] [build.gradle.kts (root)](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/build.gradle.kts)
- Add the KSP plugin to the `plugins` block with `apply false`.

#### [MODIFY] [build.gradle.kts (:app)](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/build.gradle.kts.kts)
- Apply the `com.google.devtools.ksp` plugin.
- Replace `annotationProcessor("androidx.room:room-compiler:$roomVersion")` with `ksp("androidx.room:room-compiler:$roomVersion")`.
- Optional: Update Room version to `2.8.4` for better compatibility with modern tools.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleDebug` to ensure code generation works and the project builds.
- Run the application to verify the `Room.databaseBuilder` call succeeds.

### Manual Verification
- Deploy the app to a device and navigate to a screen that uses the database (e.g., `AnalysisScreen`).
