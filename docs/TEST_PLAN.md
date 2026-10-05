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
- Network detection/candidate policy: Wi-Fi remains TLS-only; Ethernet permits only current same-device addresses on port 5555; advertised TLS is preferred; remote hosts, hostnames, loopback, invalid ports, and stale addresses are rejected; IPv6 normalization preserves link-local scope.
- Network recovery: unchanged snapshots do nothing, address/link changes reconnect automatically, and interrupted work is stopped before reconnecting without replaying compilation or console commands.

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

Still unverified: Android 11/12 devices, iPhone Safari specifically, Wireless Debugging off/on and authorization-revocation recovery, two-package batch, version-update detection, explicit console cancellation, and production-signed release.

### TV transport follow-up: 2026-09-30

- User testing after compile-capability detection was fixed exposed `TLS write returned -1` and generic operation failures. Package refresh also failed while the UI still claimed Connected.
- Transport operations now run serially with whole-operation deadlines rather than a 15-second socket-read timeout. A failed transport is discarded and exposed as unavailable with Retry; an interrupted batch stops without automatically replaying compilation.
- JVM regression tests cover concurrent calls, socket-close timeout/cancellation, no automatic replay, and interrupted-compilation batch termination.
- Installed the updated APK on the same Smart TV Pro (Android 14) and reconnected with its saved credential. A disposable Java APK completed compilation twice, including on the final build after timeout recovery. `pm art dump` reported `[status=speed] [reason=cmdline]`; the optimizer recorded `Success` with `ART_STATE` validation.
- This TV rejects `pm art help` while supporting `pm art dump`. The capability probe now inspects the optimizer's own package with the read-only dump command; Diagnostics correctly reports both compilation and ART inspection available.
- The console completed `sleep 20` with exit code 0 and retained its connection. `sleep 60` reached the 30-second operation deadline, reported a timeout with Retry instructions, and changed the connection to unavailable. Retry restored connection, shell UID 2000, and both capabilities. Startup reconnect also passed after force-stop/relaunch.
- On the final build, individual D-pad events moved focus from Edit to Optimize All with `mInputShown=false`. Select intentionally opened the editor and TV keyboard. Back dismissed the keyboard, then the editor, and restored focus to Edit with the keyboard hidden. The editor/keyboard layout was visually checked.
- Removed the disposable package entry and uninstalled its APK. All four configured user apps remained, their existing optimization records were unchanged, and test console history/output were cleared. No existing user app was optimized by the agent during this check.
- Final local gates: lint, 23 JVM tests, debug APK assembly, and APK signature verification passed.

### Ethernet implementation check: 2026-10-05 (issue #14)

- The user identified the TV as TCL C765; firmware reports TCL Smart TV Pro/G08, Android 14 (API 34). Ethernet disabled Wi-Fi and Wireless Debugging. Port 5555 refused connections until the user enabled USB debugging, then a desktop ADB connection and harmless probe succeeded.
- Updated the already installed debug APK in place without clearing data. The app automatically detected Ethernet, connected to its own current address using its existing stored credential, and displayed **Connected over Ethernet**, **Ethernet ADB**, and verified device identity. No IP was entered in the app.
- Diagnostics showed shell UID 2000, compilation available, ART inspection available, and zero TLS pairing/connect advertisements. Force-stop/relaunch automatically reconnected over Ethernet.
- The user then unplugged Ethernet, connected Wi-Fi, and enabled Wireless Debugging. The app automatically returned to its existing TLS path. A live UI inspection confirmed **Connected over Wi-Fi**, **Network: Wi-Fi**, **Wireless Debugging (TLS)**, and verified identity; no re-pairing or manual mode selection was needed.
- SHA-256 comparisons of the configured-package and optimization-record preference values confirmed they were preserved across the update and connection. No configured app was optimized, and no console command was executed during this check. The Device screen was visually checked at 1920×1080.
- Local validation: `scripts/check.ps1` (lint, 31 JVM tests, debug assembly) and five release-helper regression tests passed. Existing lint warnings remain; no tests or lint checks were disabled.
- Physical Wi-Fi → Ethernet return switching, fresh-install RSA authorization/denial, revoked wired authorization, and Ethernet interruption during compilation remain unverified on the TV. Candidate/recovery decisions and blocked-operation cancellation/no replay have offline regression coverage. Existing wireless pairing/notification/QR and Kadb mDNS lifecycle are retained; a fresh wireless pairing was not repeated during this Ethernet check.

## CI gates

- `lint`
- `testDebugUnitTest`
- `assembleDebug`

Release workflow additionally runs the same checks before building the signed release APK.
