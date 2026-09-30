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
- Keep built-in optimizer commands allow-listed; provide raw shell commands only through the explicit Advanced console.

## Supported scope (v1)

- Android 11+ phones/tablets with modern Wireless Debugging.
- Android TV / Google TV where modern Wireless Debugging is available; Android 13+ is the intended TV baseline.
- Runtime capability detection is authoritative: unsupported OEM builds should fail gracefully.
- No legacy `adb tcpip 5555` fallback in v1.
- No root requirement.

## Build

The project uses the committed Gradle 9.3.1 wrapper, Android Gradle Plugin 9.1.1, compile SDK 37, and target SDK 35. The minimum supported runtime remains Android 11 (API 30). Set `ANDROID_HOME` or a Git-ignored `local.properties` pointing to the Android SDK, then run `scripts/check.ps1` on Windows or `scripts/check.sh` on Linux/macOS. The check runs lint, JVM tests, and a debug APK build.

Kadb 2.1.4 provides TLS Wireless ADB pairing and mDNS discovery. Its `spake2-java` pairing dependency is GPL-3.0; see [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) and review redistribution terms before publishing an APK.

## Usage

Enable Developer Options and Wireless Debugging on the device. The same two pairing choices appear on phones, tablets, and TVs. **Enter code in notification** lets you keep Android's pairing-code dialog open while replying from the notification shade. It needs notification permission and a usable notification shade. **Enter code on another device** shows a QR code for a temporary local HTTP page; scan it with another phone, tablet, or computer (including an iPhone) and enter the six-digit code there. On TV, use the remote's D-pad to choose a method, then select **Open Developer Options**; the QR code remains visible beside the steps. Both methods pair only with an ADB endpoint discovered on the Android device itself. After connection, add package IDs manually, as a comma-separated list, or from discovered third-party apps. Optimize one or all installed configured packages. The app shows actual phases, completed count, elapsed compilation time, and whether ART state could be inspected. The Advanced ADB Console runs only commands you explicitly enter and has a one-time warning.

On TV, D-pad focus can pass package, filter, and console text entry without opening the keyboard. Select the relevant **Edit** action when you want to type. An unavailable ART-inspection command does not block compilation; successful runs are reported as command-validated instead.

Compilation can run for up to five minutes; other shell operations and the console have a 30-second limit. Connection failures stop the batch and show **Retry**. An interrupted compile has an unknown result and is never replayed automatically. Cancelling the console closes the connection; select Retry before the next command.

The web page is opt-in, binds to a local Wi-Fi/Ethernet address, uses a random one-time URL, permits at most three code submissions, and closes after pairing, cancellation, or three minutes. Use it only on a trusted home network: HTTP does not hide the code from a local network attacker. A phone being paired needs a **second** device to scan its QR code.

Pairing uses only mDNS-discovered endpoints on the same device. The ADB private key stays in app-private no-backup storage. Reconnect re-discovers the dynamic port, probes the shell, and checks the saved device identity. If a stable shell identifier is unavailable, the app labels its weaker model-based check.

One Android 16 Samsung tablet has passed both notification and second-device web pairing, reconnect, console, and a disposable-app compile check. An Android 14 TV has passed launcher, D-pad navigation, web pairing, and reconnect checks. The remaining device matrix, iPhone Safari specifically, and production release signing are tracked in [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md).

## Repository handoff

Coding agents should read, in order:

1. [`AGENTS.md`](AGENTS.md)
2. [`docs/SPEC.md`](docs/SPEC.md)
3. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
4. [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md)
5. [implementation issue #1](https://github.com/daermond/android-art-optimizer/issues/1)

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
