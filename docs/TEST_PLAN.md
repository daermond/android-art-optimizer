# Test Plan

## Unit tests (required)

- Package ID validation: valid IDs accepted; whitespace/metacharacter/injection attempts rejected.
- CSV package parsing: trim, deduplicate, preserve valid entries, report invalid entries.
- Safe command rendering produces exact expected commands only from typed inputs.
- Connection state machine: first pairing, reconnect success, transient discovery failure, invalid auth, wrong-device handling, Pair Again.
- Optimization state machine: installed success, missing skip, compile failure, validation unavailable, full validation success.
- Batch progress: x/y counts and terminal states; no fabricated per-command percentage.
- Concurrent ADB operations are serialized; a blocked command is interrupted by timeout/cancellation; a failed command is not replayed. Connection loss stops the batch and does not record success for an interrupted compile.
- Version tracking: updated package becomes "optimization recommended".
- Result parser: tolerate extra/unknown OEM output without crashing.
- Secret/log redaction.
- Console normalization: optional leading `adb shell ` is stripped only in console mode; ordinary shell command is preserved.
- Console execution state: success, failure, unavailable exit code, cancellation/timeout.
- Raw console command path cannot be reached from built-in optimizer command factories or external intents.
- Console history is bounded, persisted locally as designed, and can be fully cleared.
- Local pairing page rejects unknown session URLs, cross-origin posts, malformed codes, and expired sessions; no code appears in the URL or response.

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
13. On a phone, pair while leaving the Settings code dialog open and entering the code through the notification reply. Verify permission denial has a useful fallback message.
14. On a phone/tablet/TV, open the QR URL on a second device (including iPhone Safari where available), enter the code, and verify the page closes after pairing, cancellation, and timeout.
15. Verify both choices are shown on every form factor; unavailable notifications and inaccessible local network produce actionable errors.
16. On TV, verify the app appears in the TV launcher with its banner; use only D-pad/Select/Back to reach every tab, pairing choice, Cancel, app actions, and Advanced warning. The focused control must be obvious and long pages must scroll to it.
17. On TV, start QR pairing and verify the complete code stays visible beside the instructions as the user navigates. Leave for Settings via Open Developer Options, return with Back, and verify the pairing session is still available or has an actionable error.
18. On TV, D-pad past package, filter, and console edit controls without opening the keyboard. Select an edit control to open the keyboard intentionally; Back/Done returns focus to the page and D-pad navigation continues.
19. On an OEM device whose `cmd package help` prints a `compile [...]` usage entry but exits nonzero, verify the compile capability is available while unsupported ART inspection remains an optional command-validation fallback.
20. Leave a harmless console command quiet for more than 15 seconds, then verify its output and the next package refresh. Compile a disposable app and verify completion. Disconnect Wireless Debugging during a command and verify an actionable Retry state; no remaining batch package should start automatically.

### Real-device run: 2026-09-29

Samsung SM-X210 tablet, Android 16 (API 36):

- Installed and launched the debug APK without a startup crash.
- Paired through the tablet's own Wireless Debugging service; Settings and the optimizer had to remain in split screen because the pairing endpoint disappeared when Settings went to the background.
- Connected with a stable shell identity, then reconnected automatically after force-stop and relaunch.
- Discovered third-party packages. The Advanced console normalized `adb shell id`, returned shell UID 2000 and exit code 0, and saved the normalized command in local history.
- The optimizer skipped its own package. A disposable Java test app completed `speed` compilation and recorded `Validation: COMMAND`; this Samsung build did not expose ART inspection through the app's capability probe.
- Removed both test entries and uninstalled the disposable app. No existing user app was optimized.
- The new notification-reply path paired successfully while the Settings code dialog remained open.
- The temporary QR page loaded from a second device over the local network. The first mobile-browser form submission returned 403 because of strict request-header validation; after making the form handler tolerant of omitted or opaque Origin headers and optional Content-Type while retaining the random URL and Host check, the second-device code submission paired successfully. The app saved the pairing and stopped the temporary service. The exact phone browser was not recorded.

Smart TV Pro (G08), Android 14 (API 34), 1920×1080 display, same date:

- Installed the debug APK and launched it through `LEANBACK_LAUNCHER`. Package inspection confirmed the launcher icon, 320×180 TV banner, and optional touchscreen/TV/Wi-Fi features.
- D-pad focus began on Device with a high-contrast ring. Down navigation reached Retry, notification pairing, web pairing, Open Developer Options, and Cancel; focus scrolling kept those controls visible. Select opened Developer Options, and Back returned to the optimizer.
- Starting web pairing with Select displayed the QR code beside the steps without losing focus on the selected action. Cancel removed the code.
- In a fresh session, Open Developer Options reached the TV's Wireless Debugging settings. The local web page accepted the Settings pairing code; the optimizer reported `connected` with verified identity. After force-stop and relaunch it re-discovered the dynamic endpoint and reconnected. No TV app was optimized.
- D-pad selection of Apps, Diagnostics, and Advanced kept focus on the selected tab. The updated APK was also reinstalled on the Samsung tablet and still opened in the connected state.
- Follow-up after four applications were configured: all four package IDs and installed-version entries survived the update. This TV returned exit code 255 from `cmd package help` despite listing `compile`; the capability probe now recognizes its usage entry. Diagnostics reports compilation available, and Optimize All is enabled. ART inspection is still unavailable and correctly uses command-level validation. Background discovery remains active while connected.
- D-pad focus passed the package edit action to Optimize All without opening the keyboard. Select opened the explicit editor and keyboard; Back dismissed them, and D-pad navigation resumed. No configured application was optimized during this follow-up.

Still unverified: Android 11/12 devices, TV optimization, iPhone Safari specifically, Wireless Debugging off/on and authorization-revocation recovery, two-package batch, version-update detection, console failure/cancel/clear behavior, and production-signed release.

### TV transport follow-up: 2026-09-30

- User testing after compile-capability detection was fixed exposed `TLS write returned -1` and generic operation failures. Package refresh also failed while the UI still claimed Connected.
- Transport operations now run serially with whole-operation deadlines rather than a 15-second socket-read timeout. A failed transport is discarded and exposed as unavailable with Retry; an interrupted batch stops without automatically replaying compilation.
- JVM regression tests cover concurrent calls, socket-close timeout/cancellation, no automatic replay, and interrupted-compilation batch termination. Verification on the updated TV build is pending.

## CI gates

- `lint`
- `testDebugUnitTest`
- `assembleDebug`

Release workflow additionally runs the same checks before building the signed release APK.
