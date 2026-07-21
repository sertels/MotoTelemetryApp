# Migrate from GoogleSignIn to Credential Manager

This plan outlines the migration from the deprecated `GoogleSignIn` API to the modern `Credential Manager` and `Google Identity Services` for authentication and Google Drive authorization.

## User Review Required

> [!IMPORTANT]
> The modern `Credential Manager` flow involves a few more steps for authorization (scopes) compared to the older one-stop-shop `GoogleSignIn`. We will use `Credential Manager` for the user picker and `AuthorizationClient` for requesting the Drive scope.

## Proposed Changes

### [Dependencies] - Auth & Credentials

#### [MODIFY] [build.gradle.kts](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/build.gradle.kts)
Add modern identity and credential libraries:
- `androidx.credentials:credentials:1.6.0`
- `androidx.credentials:credentials-play-services-auth:1.6.0`
- `com.google.android.libraries.identity.googleid:googleid:1.2.0`

### [Core] - Identity Management

#### [MODIFY] [GoogleDriveManager.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/GoogleDriveManager.kt)
- Remove `GoogleSignIn` dependencies.
- Update `uploadDatabase` to accept an `Account` object or an authorized email string.
- (Optional) Refactor to use the authorized `GoogleAccountCredential` directly.

### [UI] - Authentication Flow

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/MainActivity.kt)
- Implement `CredentialManager` to handle user sign-in.
- Implement `Identity.getAuthorizationClient` to request the `DRIVE_APPDATA` scope.
- Update the `onBackup` trigger to initiate this new sequence.

#### [MODIFY] [DashboardViewModel.kt](file:///C:/Users/TKA/AndroidStudioProjects/MotoTelemetryApp/app/src/main/java/com/example/mototelemetryapp/DashboardViewModel.kt)
- Update `backupToCloud` signature to handle the new authorized account data.

## Verification Plan

### Manual Verification
- Deploy to device.
- Click "Backup to Drive".
- Verify the new "Credential Manager" bottom sheet appears for account selection.
- Confirm the permission dialog for Drive access is shown.
- Verify the backup is still successfully uploaded to the Drive App Data folder.
