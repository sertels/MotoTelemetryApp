# Route Visualization & Navigation Walkthrough

We have successfully integrated Google Maps to visualize recorded motorcycle routes and added a navigation system to the app.

## Changes Made

### 1. Google Maps Integration
- **File:** [HistoryScreen.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/ui/HistoryScreen.kt)
- **Features:**
    - Displays a map centered on the recorded route.
    - Draws a `Polyline` (red line) connecting all recorded GPS coordinates.
    - Handles empty states with a default map view.

### 2. Navigation System
- **File:** [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- **Features:**
    - Integrated `Jetpack Navigation` with `NavHost`.
    - Added a `NavigationBar` (Bottom Menu) with "Panel" (Live Dashboard) and "Geçmiş" (History) tabs.
    - The navigation bar appears only when the `TelemetryService` is active.

### 3. Data Flow Enhancements
- **File:** [DashboardViewModel.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/DashboardViewModel.kt)
- **Features:** Added `fetchHistory` to observe all records from the Room database and expose them to the UI.

### 4. Configuration
- **File:** [AndroidManifest.xml](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/AndroidManifest.xml)
- **Action:** Added a placeholder for the Google Maps API Key.

## Verification Steps

1. **API Key:**
    > [!IMPORTANT]
    > You MUST replace `YOUR_API_KEY_HERE` in `AndroidManifest.xml` with a valid key from the Google Cloud Console for the map to load.
2. **Navigation:** Start the service, and use the bottom bar to switch between the live dashboard and the history map.
3. **Route Check:** After a ride (or using a GPS simulator), go to the "Geçmiş" tab to see your path drawn on the map.

## Project Status
- **Core:** Bluetooth OBD2, Sensors (Lean Angle), GPS.
- **Persistence:** Room DB with Speed, RPM, Throttle, Brake (F/R), Lean, G-Force.
- **UI:** Live Dashboard + Route History Map.
