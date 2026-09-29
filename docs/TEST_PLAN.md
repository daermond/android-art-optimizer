# Test Plan

## Unit tests (required)

- Package ID validation: valid IDs accepted; whitespace/metacharacter/injection attempts rejected.
- CSV package parsing: trim, deduplicate, preserve valid entries, report invalid entries.
- Safe command rendering produces exact expected commands only from typed inputs.
- Connection state machine: first pairing, reconnect success, transient discovery failure, invalid auth, wrong-device handling, Pair Again.
- Optimization state machine: installed success, missing skip, compile failure, validation unavailable, full validation success.
- Batch progress: x/y counts and terminal states; no fabricated per-command percentage.
- Version tracking: updated package becomes "optimization recommended".
- Result parser: tolerate extra/unknown OEM output without crashing.
- Secret/log redaction.
- Console normalization: optional leading `adb shell ` is stripped only in console mode; ordinary shell command is preserved.
- Console execution state: success, failure, unavailable exit code, cancellation/timeout.
- Raw console command path cannot be reached from built-in optimizer command factories or external intents.
- Console history is bounded, persisted locally as designed, and can be fully cleared.

## Instrumented tests (where practical)

- Compose navigation/state rendering.
- DataStore persistence/reload.
- External intent requires explicit confirmation and cannot execute arbitrary console text.
- Advanced console renders running/completed/error output states and clear/copy actions.

## Manual real-device matrix before v1 release

At minimum:

1. Android 11/12 phone or tablet with Wireless Debugging.
2. Current Android phone/tablet.
3. Android 13+ Android/Google TV device if available.
4. Pair -> optimize -> app restart -> automatic reconnect.
5. Turn Wireless Debugging off/on and recover with Retry.
6. Revoke ADB authorization and recover with Pair Again.
7. Optimize at least two different third-party packages in one batch.
8. Upgrade one target app and verify update detection.
9. Run `pm list packages -3` in Advanced ADB Console and verify output is visible/copyable.
10. Run a harmless failing/unknown command and verify failure/result is shown without crashing.
11. Paste `adb shell pm list packages -3`; verify optional prefix normalization and execution.
12. Verify console history can be cleared and no command auto-runs after restart/reconnect.

### Real-device run: 2026-09-29

Samsung SM-X210 tablet, Android 16 (API 36):

- Installed and launched the debug APK without a startup crash.
- Paired through the tablet's own Wireless Debugging service; Settings and the optimizer had to remain in split screen because the pairing endpoint disappeared when Settings went to the background.
- Connected with a stable shell identity, then reconnected automatically after force-stop and relaunch.
- Discovered third-party packages. The Advanced console normalized `adb shell id`, returned shell UID 2000 and exit code 0, and saved the normalized command in local history.
- The optimizer skipped its own package. A disposable Java test app completed `speed` compilation and recorded `Validation: COMMAND`; this Samsung build did not expose ART inspection through the app's capability probe.
- Removed both test entries and uninstalled the disposable app. No existing user app was optimized.

Still unverified: Android 11/12 and TV devices, Wireless Debugging off/on and authorization-revocation recovery, two-package batch, version-update detection, console failure/cancel/clear behavior, and production-signed release.

## CI gates

- `lint`
- `testDebugUnitTest`
- `assembleDebug`

Release workflow additionally runs the same checks before building the signed release APK.
