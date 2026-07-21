# Route Visualization on Map Implementation Plan

This plan outlines the integration of Google Maps to visualize recorded motorcycle routes and telemetry data.

## User Review Required

> [!IMPORTANT]
> - **Google Maps API Key:** To display the map, you will need a Google Maps API Key. I will show you where to add it, but you'll need to generate one from the [Google Cloud Console](https://console.cloud.google.com/).
> - **Navigation:** We will add a bottom navigation bar to switch between the **Dashboard** (Live Data) and **History** (Recorded Routes).

## Proposed Changes

### [Dependencies] - Map & Navigation

#### [MODIFY] [build.gradle.kts](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/build.gradle.kts)
Add dependencies for:
- `com.google.maps.android:maps-compose`
- `com.google.android.gms:play-services-maps`
- `androidx.navigation:navigation-compose`

### [UI] - Navigation & Map Screen

#### [NEW] [HistoryScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/HistoryScreen.kt)
A new screen that:
- Fetches all records from Room DB.
- Displays a Google Map using `GoogleMap` composable.
- Draws a `Polyline` representing the motorcycle's path.
- (Optional) Color-codes the path based on speed or lean angle (e.g., red for high speed, blue for slow).

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Implement `NavHost` to manage screen switching.
- Add a `NavigationBar` (Bottom Navigation) to toggle between Live Dashboard and Route History.

### [Data] - DAO Enhancements

#### [MODIFY] [TelemetryDao.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/data/TelemetryDao.kt)
- Add a query to group records by session (if we implement session tracking) or just fetch the most recent trip.

## Verification Plan

### Manual Verification
- Deploy to device.
- Navigate to the "History" tab.
- Verify that a map appears (with a valid API key).
- Confirm that the recorded path is drawn correctly following the GPS coordinates.
