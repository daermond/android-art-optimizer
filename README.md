# Android ART Optimizer

A small standalone Android utility that uses the device's own modern Wireless ADB interface to request ART/AOT optimization for user-selected applications.

## Goals

- Pair once with the local Android device using Wireless Debugging.
- Re-discover the current ADB endpoint with mDNS on later launches.
- Validate that the saved pairing still works every time the app starts.
- Let the user select any installed third-party application or add package IDs manually/bulk as comma-separated text.
- Optimize selected packages with `cmd package compile -m speed -f <package>`.
- Show honest, phase-based progress and per-package results.
- Validate the resulting ART state where the Android version exposes enough information.
- Keep ADB capability isolated in this standalone utility; do not expose a general-purpose shell.

## Supported scope (v1)

- Android 11+ phones/tablets with modern Wireless Debugging.
- Android TV / Google TV where modern Wireless Debugging is available; Android 13+ is the intended TV baseline.
- Runtime capability detection is authoritative: unsupported OEM builds should fail gracefully.
- No legacy `adb tcpip 5555` fallback in v1.
- No root requirement.

## Repository handoff

The scaffold intentionally lets CI provision Gradle 8.9; the implementation issue requires the coding agent to generate and commit the standard Gradle wrapper early.

Coding agents should read, in order:

1. [`AGENTS.md`](AGENTS.md)
2. [`docs/SPEC.md`](docs/SPEC.md)
3. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
4. [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md)
5. the open implementation issue created from [`docs/IMPLEMENTATION_ISSUE.md`](docs/IMPLEMENTATION_ISSUE.md)

Do not redesign settled product decisions unless implementation proves one impossible.

## Local checks

Linux/macOS:

```bash
./scripts/check.sh
```

PowerShell:

```powershell
./scripts/check.ps1
```

## GitHub bootstrap

If this folder is not yet a GitHub repository, install/authenticate the GitHub CLI, then run:

```powershell
./scripts/bootstrap-github.ps1
```

or:

```bash
./scripts/bootstrap-github.sh
```

The script creates a private `android-art-optimizer` repository, pushes `main`, creates useful labels, and opens the implementation issue from `docs/IMPLEMENTATION_ISSUE.md`.

## Releases

CI runs lint, unit tests, and a debug build on pushes and pull requests.

Tagged releases (`v*`) build a signed release APK and create a GitHub Release. Configure signing once with `scripts/setup-github-signing.sh` or the equivalent GitHub repository secrets described in [`docs/RELEASE.md`](docs/RELEASE.md).


## Advanced Custom ADB Console

The v1 plan includes an explicit Advanced console for running one-shot local `adb shell` commands and viewing stdout/stderr/result. Built-in optimizer commands remain isolated and allow-listed.
