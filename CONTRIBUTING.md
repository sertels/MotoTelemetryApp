# Contributing

This started as a single-bike project (Voge 900DSX / LX900-A), but since it shares the BMW F900
platform and Loncin 4M96001 engine with other bikes, fixes or new confirmed PIDs from other
owners on that platform are welcome.

## Before you start

- Read `CLAUDE.md` for the architecture (Service → ViewModel → Compose UI over StateFlow) and the
  conventions the codebase already follows - PRs that fit the existing patterns are much easier to
  review than ones that introduce a new way of doing the same thing.
- If your change touches OBD/UDS parsing or CAN monitoring, you almost certainly need real
  hardware (a Bluetooth ELM327 adapter and the bike itself) to verify it - there's no way to
  emulate a real ELM327 or a real motorcycle. Say in the PR description how you tested it.
- For a new confirmed PID/DID, add it to `docs/confirmed_pids.md` with your vehicle's reference
  info (VIN/calibration ID via Mode 09, dash cluster firmware version if you have it) - this list
  is only useful if entries say what they were actually verified against.

## Building and testing

```powershell
.\gradlew.bat assembleDebug            # build
.\gradlew.bat testDebugUnitTest        # unit tests (JVM, no device needed)
.\gradlew.bat connectedDebugAndroidTest # instrumented tests (needs a connected device/emulator)
```

You'll need your own Google Maps API key (see the Setup section in `README.md`) to build at all,
and optionally a Google Cloud OAuth setup if you're touching the Drive backup/restore feature.

## Pull requests

- Keep PRs focused - one fix or feature per PR is easier to review and revert if needed.
- Explain *why*, not just *what*, in the description - the same standard the codebase's own
  commit messages and comments hold to.
- UI/navigation changes should include a screenshot or a short description of how you verified the
  actual interaction path, not just that it builds.

## Reporting issues

Open a GitHub issue. For a bug on your own bike, include: which BMW-F900-platform bike/model year,
dash cluster firmware version if known (Settings won't show this - check the dash's own info
menu), and whatever you can pull from **Settings → Tanılama Günlüğünü Paylaş** (diagnostic log).
