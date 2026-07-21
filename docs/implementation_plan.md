# Testing Strategy Plan

This plan outlines the creation of unit tests to verify the core logic of the Moto Telemetry application, specifically the data parsing from OBD2 responses and coordinate handling.

## User Review Required

> [!NOTE]
> Since we cannot connect to a physical ELM327 adapter or a motorcycle in this environment, we will use **Unit Tests** with simulated data to verify that our parsers and logic work correctly.

## Proposed Changes

### [Tests] - Core Logic Verification

#### [NEW] [OBDParsingTest.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/test/java/com/example/mototelemetryapp/OBDParsingTest.kt)
Create unit tests to verify:
- **RPM Parsing:** Input `"410C1AF8"` -> Expected result.
- **Speed Parsing:** Input `"410D32"` -> Expected `50`.
- **Gear Parsing:** Input `"6243F703"` -> Expected `3`.
- **Throttle Parsing:** Input `"4111FF"` -> Expected `100`.
- **Brake Parsing:** Input `"622B053C"` -> Expected pressure in bar.
- **Lean Angle (Bike) Parsing:** Input `"62D10D01F4"` -> Expected `50.0` or similar signed 16-bit handling.

#### [NEW] [SensorLogicTest.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/test/java/com/example/mototelemetryapp/SensorLogicTest.kt)
Verify the mathematical formulas used for G-force and lean angle if they can be isolated from Android's `SensorManager`.

## Verification Plan

### Automated Tests
- Run `./gradlew test` to execute all unit tests.
- Ensure all tests pass.
