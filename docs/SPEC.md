# Product Specification — Android ART Optimizer v1

## 1. Purpose

Create a small standalone Android utility that improves ART/Dex execution for explicitly selected applications by connecting to the device's own ADB daemon and running Android package compilation commands. Modern Wireless Debugging is the Wi-Fi path; issue #14 adds standard authenticated TCP ADB on the device's Ethernet addresses where firmware exposes it.

It is intentionally generic. Expected targets include Nuvio variants, SmartTube, forks, and other sideloaded or Play-distributed applications.

## 2. Non-goals

- Host-side/general-purpose ADB tooling beyond the explicit local shell console described in section 15.
- Root management.
- Enabling `adb tcpip 5555`, physical USB transports, USB-first pairing, or Android <=10 compatibility. Issue #14 permits connecting to an already exposed same-device Ethernet ADB listener on port 5555.
- Native `.so`, GPU, WebView/JavaScript, codec, or network optimization.
- Automatic optimization of every installed package.
- Silent execution requested by another application.

## 3. Platform scope

- App `minSdk`: API 30 (Android 11).
- Modern Wireless Debugging is required for Wi-Fi. Ethernet requires firmware exposing network ADB, sometimes controlled by the TV's USB debugging setting.
- TV support is capability based; Android 13+ is the intended baseline.
- At launch, probe actual support. Never assume an OEM exposes all AOSP behavior solely from SDK level.

## 4. First-run pairing

The app must guide the user through:

1. Enable Developer Options.
2. Enable Wireless Debugging.
3. Choose "Pair device with pairing code" in Android settings.
4. App discovers `_adb-tls-pairing._tcp` via mDNS.
5. User chooses either notification reply on the Android device or a temporary local web page reached by QR code on a second device. The same two choices are available on phones, tablets, and TVs. The app receives the six-digit code without closing the Settings pairing dialog.
6. App pairs and stores its ADB credential securely.
7. App discovers `_adb-tls-connect._tcp` via mDNS.
8. App connects, executes a harmless shell probe, and records a device identity profile.

Do not persist a dynamic ADB port as the primary reconnect mechanism.

The temporary HTTP page is user-started, bound only to a local Wi-Fi/Ethernet address, protected by a high-entropy one-time URL, rate-limited to three code submissions, and closed after success, cancellation, or a short timeout. It must accept pairing codes only; never expose shell commands or stored credentials. Explain that HTTP on an untrusted local network can reveal the pairing code. Notification input requires visible notifications and may be unavailable on some devices.

## 5. Startup reconnect / validation

On every app start:

1. Load saved pairing metadata/key.
2. Discover current ADB connect endpoints with mDNS.
3. Attempt authentication with the stored key.
4. Run a harmless shell probe such as `echo` + device properties.
5. Verify the connected device matches the stored device profile when a stable identifier is available.
6. Produce one of these user-visible states:
   - Connected / verified.
   - Searching.
   - Wireless Debugging unavailable/off.
   - Authentication rejected / pairing invalid.
   - Wrong device / identity mismatch.
   - Not paired.
7. Provide Retry and Pair Again actions where appropriate.

A transient failure must not erase pairing/configuration automatically.

### Ethernet and automatic network detection (issue #14)

- Detect Wi-Fi/Ethernet links and addresses with runtime network callbacks; public internet is not required.
- Keep the existing wireless pairing and mDNS flow. Try a current local TLS endpoint first when available; otherwise Ethernet may connect only to port 5555 on a current Ethernet address of this device.
- A fresh Ethernet session creates/reuses the same app-protected RSA credential and lets Android request authorization. Never scan other devices, accept arbitrary hosts, or enable a debugging service through shell commands.
- Record the device profile only after the harmless probe and identity validation succeed. Preserve saved identity, key, packages, and records on failure.
- Reconnect automatically on network/address changes. Cancel and join interrupted optimization/console jobs before reconnecting; never resume a batch or replay a command. Show that an interrupted operation may have an unknown result.
- Show the actual connected link (Wi-Fi/Ethernet), ADB path, and actionable debugging/authorization text. Ethernet ADB is unencrypted and should be used on a trusted network.
- Unsupported Ethernet debugging is a normal capability failure; guide the user to USB/network debugging if their firmware supports it, or Wi-Fi Wireless Debugging otherwise.

## 6. Device identity

Collect only what is useful for local validation and display, for example:

- manufacturer
- model
- Android release
- SDK
- a stable shell-visible identifier when available

If no safe stable identifier is available, fall back to a documented weaker fingerprint and show lower-confidence validation rather than inventing identity certainty.

## 7. Application selection

The user can maintain an arbitrary list of package IDs.

### 7.1 Discover installed apps

Use ADB/package manager, not Android app package visibility, to discover third-party apps where practical. Preferred basis:

`pm list packages -3`

Resolve human-readable labels/icons through safe Android APIs when possible; package ID is always the canonical identity.

### 7.2 Manual package entry

Allow one package ID to be entered manually.

### 7.3 Bulk entry

Allow comma-separated input, for example:

`com.nuvio.tv, app.smarttube, com.example.fork`

Trim whitespace, remove duplicates, and validate each package name before storing or executing any command.

### 7.4 Missing applications

Configured-but-not-installed packages stay in the list in a neutral/missing state and can be removed manually. Never auto-delete them.

## 8. Package-name validation

All command-producing code must accept only validated package identifiers. Centralize this validation.

Reject shell metacharacters, whitespace, command substitutions, separators, or other untrusted fragments.

No UI text is ever appended directly to an ADB shell command.

## 9. Optimization

Default v1 operation per installed selected package:

1. Validate package ID.
2. Confirm installed (`pm path <package>` or equivalent).
3. Read version metadata.
4. Inspect current ART state when supported.
5. Optionally force-stop the target app before compilation. Do not force-stop the optimizer itself.
6. Execute `cmd package compile -m speed -f <package>`; use verbose output where supported and useful.
7. Capture stdout/stderr/exit/result.
8. Inspect resulting compiler state where supported (for example modern ART service tooling).
9. Save last optimized package version, mode, result, timestamp, and useful metrics.

The compile command factory must be allow-listed and unit-tested.

## 10. Compilation mode

- Default: `speed`.
- Advanced UI may later expose additional filters, but v1 only needs `speed` unless trivial to support safely.
- Never claim this optimizes native libraries, codecs, GPU work, networking, or WebView execution.

## 11. Progress model

Do not fake a byte/percent compile progress value when Android does not expose one.

### Per package phases

Suggested phases:

- Queued
- Validating package
- Reading version/current state
- Stopping app
- Compiling
- Validating result
- Completed / Failed / Skipped

Show elapsed compile time while compilation is running.

### Batch progress

For N selected apps, real overall progress may be shown as completed count / N and a derived percentage. The active package uses an indeterminate progress indicator while `cmd package compile` is executing.

## 12. Result validation

Use the strongest available method at runtime.

- Command execution/result is mandatory.
- On modern ART-service devices, inspect package ART state (for example `pm art dump <package>` if available).
- Parse verbose compile output where available for compiler filter/status/timing/size metrics.
- If full state inspection is unavailable, clearly report "command validation" rather than claiming full ART-state verification.

Capability probing is preferred over broad OS-version assumptions.

## 13. Version-aware state

Persist per package:

- package ID
- last seen version name/code
- last optimized version code
- compiler filter requested
- timestamp
- result/validation level

If installed version code differs from the last successfully optimized version, show "Updated since optimization" and make re-optimization easy.

Do not automatically recompile on update in v1.

## 14. Main UI

Minimum screens:

### Device / connection

- model + Android version
- pairing/connection status
- reconnect progress/status
- Retry
- Pair Again
- Diagnostics

### Apps

Each configured app:

- label when resolvable
- package ID
- installed/missing
- installed version
- optimization status
- last result/time
- Optimize action
- Remove action

Actions:

- Add installed app
- Add package manually
- Bulk add comma-separated packages
- Optimize All eligible apps

### Run progress

Show per-package phases, current package elapsed time, and batch x/y status.

### Diagnostics

Show non-secret diagnostics:

- SDK / Android
- model
- Wireless ADB discovery state
- connection/authentication state
- shell UID if safe/useful
- compile command capability
- ART inspection capability

Never show private ADB key material.

## 15. Advanced Custom ADB Console

Provide an explicit **Advanced → ADB Console** for expert/manual use. This is intentionally separate from the typed/allow-listed optimizer operations.

### 15.1 Scope

- Execute one-shot shell commands on the already paired **local device** through the existing ADB transport.
- Primary input is the command that would normally follow `adb shell`, for example:
  - `pm list packages -3`
  - `cmd package compile -m speed -f app.smarttube`
  - `dumpsys package app.smarttube`
  - `settings get global ...`
- For convenience, if the user pastes a command beginning with `adb shell `, the UI may strip that prefix before execution.
- v1 does **not** need host-side ADB subcommands such as `adb push`, `adb pull`, `adb install`, `adb forward`, or interactive shells. Those can be separate future features.

### 15.2 Console UX

Minimum console features:

- Multiline command input.
- Explicit **Run** action; never execute merely on paste/open.
- Show running state and elapsed time.
- Show captured stdout and stderr (or a combined clearly labelled output if the ADB library cannot separate them).
- Show completion/result/exit status when available. If the transport cannot expose a numeric shell exit code, label this accurately rather than inventing one.
- Monospace, selectable/copyable output.
- Clear output action.
- Keep a small local command history for convenience; allow clearing it. Do not sync history or include it in diagnostics unless the user explicitly exports it.
- Support cancellation/timeout where the selected ADB library makes this practical.
- Bound retained output/history so a command producing huge output cannot exhaust memory/storage.

### 15.3 Safety boundary

The console is intentionally powerful and may modify device settings or packages. Therefore:

- Put it under an Advanced section and show a one-time warning before first use.
- Raw console text is permitted **only** through this explicit user-operated console path.
- Built-in optimizer features continue to use typed/allow-listed commands and strict package validation.
- External intents/deep links may never execute or pre-authorize arbitrary console commands.
- Never auto-run a previous command after reconnect/restart.
- Never log pairing secrets/private keys. Avoid logging raw console commands/output outside the console history.

## 16. External integration

Optional but desirable v1 surface: explicit Android intent/deep link to request opening the optimizer for one package.

Example semantics:

- caller requests package `com.nuvio.tv`
- optimizer opens focused on that package
- user must explicitly press Optimize/confirm

No caller may trigger background/silent ADB commands.

## 17. Security requirements

- Store ADB private key/credential using Android secure storage appropriate for the chosen ADB library.
- Never log private key contents or pairing secrets.
- Central typed command factory / allow-list for all built-in optimizer actions.
- Strict package validation for optimizer/package actions.
- Raw shell text is accepted only in the explicit Advanced ADB Console and never reused by built-in workflows or external intents.
- External requests require confirmation.
- Redact secrets from diagnostics/exported logs.
- Network connections are only to locally discovered TLS ADB endpoints or port 5555 on current same-device Ethernet addresses, selected/verified by the app.

## 18. Error handling

Expected errors must become actionable UI states:

- Wireless Debugging disabled.
- Pairing endpoint not found.
- Connect endpoint not found.
- Pairing code invalid/expired.
- Authentication rejected.
- mDNS unavailable or OEM behavior incompatible.
- package not installed.
- compile command unsupported.
- compile command failed.
- validation unavailable.
- target updated during operation.

Never crash because a shell command/output differs from AOSP examples.

## 18. Persistence

Persist only:

- device profile
- ADB credential through secure mechanism
- configured packages
- optimization records/preferences

Use DataStore/Room only as justified by complexity; prefer the smallest maintainable solution.

## 19. Definition of done for v1

- Fresh install can pair to a compatible same device without a PC.
- Relaunch automatically re-discovers and reconnects with the saved key.
- Pair Again recovers from invalid pairing.
- User can discover third-party apps and manually/bulk add package IDs.
- Package IDs are validated and deduplicated.
- User can optimize one or all eligible configured apps.
- Progress is truthful and phase-based.
- Result is validated to the strongest available level and displayed clearly.
- Version changes are detected and flagged for re-optimization.
- Arbitrary shell execution exists only in the explicit Advanced ADB Console; no external/public API can execute it.
- Unit tests cover parsing, state transitions, command generation, result parsing, and version-state behavior.
- CI passes lint/tests/debug build.
- Tagged GitHub release produces a signed APK and checksum.
