# Security Policy

This is a hobby telemetry app for a single motorcycle model, not a service handling sensitive
data beyond the rider's own ride history and OAuth tokens for their own Google Drive.

## Reporting a vulnerability

Please use GitHub's [private vulnerability reporting](https://github.com/sertels/MotoTelemetryApp/security/advisories/new)
rather than a public issue, so a fix can land before details are public. You'll get a response as
soon as reasonably possible - this is maintained by one person in their spare time, not a company
with an SLA.

## Scope

Realistic areas of concern for this app: the OAuth flow and Drive API usage in
`GoogleDriveManager.kt`/`MainActivity.kt`, and anything that could let a nearby Bluetooth device
impersonate the paired OBD adapter. UDS/OBD parsing bugs that only affect the rider's own dashboard
display are lower priority than anything touching credentials or writing to storage the app
doesn't own.
