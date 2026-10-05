## Goal

Implement v1 of the standalone **Android ART Optimizer** according to `docs/SPEC.md` and `docs/ARCHITECTURE.md`.

The original v1 scope is retained except for the explicitly authorized [Ethernet extension in issue #14](https://github.com/daermond/android-art-optimizer/issues/14): standard TCP ADB on the device's current Ethernet addresses, port 5555, where firmware already exposes it. Preserve the working modern wireless pairing flow; no physical USB/root transport, LAN scan, or `adb tcpip` enablement is added. Keep the generic package list and explicitly user-operated Advanced ADB Console described in the spec.

## Deliverables

- [ ] Generate and commit a Gradle 8.9 wrapper (`gradlew`, `gradlew.bat`, wrapper files).
- [ ] Modern same-device Wireless ADB pairing using mDNS + pairing code.
- [ ] Secure persisted ADB credential and automatic reconnect/identity validation on app start.
- [ ] Clear connection progress/error states with Retry and Pair Again.
- [ ] Runtime capability probe for package compilation and ART-state validation.
- [ ] Installed third-party application discovery.
- [ ] Manual package entry and comma-separated bulk entry.
- [ ] Strict package ID validation + deduplication.
- [ ] Persist configured packages and last optimization metadata.
- [ ] Single-app optimization with `cmd package compile -m speed -f <package>`.
- [ ] Batch Optimize All with truthful x/y + phase-based progress and elapsed compile time.
- [ ] Strongest available post-compile validation; clearly distinguish full ART validation from command-only validation.
- [ ] Detect app version changes after a successful optimization and flag re-optimization.
- [ ] Diagnostics screen without secrets.
- [ ] Advanced ADB Console that executes user-entered one-shot local `adb shell` commands through the existing paired connection.
- [ ] Console shows running state, elapsed time, stdout/stderr or accurately labelled combined output, and exit/result status when available.
- [ ] Console supports copy/select output, clear output, bounded local command history + clear-history, and cancellation/timeout where practical.
- [ ] Console accepts raw text only through its explicit Advanced UI; built-in optimizer operations remain typed/allow-listed; external intents cannot execute console commands.
- [ ] Optional explicit intent/deep-link request for one package, requiring user confirmation before execution.
- [ ] Unit tests listed in `docs/TEST_PLAN.md`.
- [ ] CI green: lint + unit tests + debug APK.
- [ ] Signed tagged release workflow produces APK + SHA-256.
- [ ] Update README and dependency/license record.

## Technical constraints

- Kotlin + Jetpack Compose.
- API 30 minimum.
- Android 13+ intended TV baseline, but feature support must be probed at runtime.
- Keep ADB implementation behind an `AdbTransport` abstraction.
- Evaluate Kadb first for Wireless ADB/mDNS; if another library is chosen, document why and its license.
- Central typed/allow-listed shell command renderer for built-in features. Raw shell input is permitted only in the isolated Advanced ADB Console use case; never mix the two paths.
- Never log pairing codes, private ADB key material, passwords, or other secrets.
- No silent optimization triggered from external intents.

## Agent workflow

1. Read `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md`, and `docs/TEST_PLAN.md`.
2. Implement in small vertical slices with tests.
3. Keep PR descriptions focused on changed behavior rather than repeating the full specification.
4. Run `./scripts/check.sh` (or PowerShell equivalent) before completion.

## Acceptance test

On a fresh compatible Android device, a user can pair locally without a PC, add two arbitrary third-party apps, optimize both, see phase-based progress/results, run a manual console command such as `pm list packages -3` and view its response, close/reopen the optimizer, automatically reconnect with the saved pairing, and see whether each installed app still matches its last optimized version.
