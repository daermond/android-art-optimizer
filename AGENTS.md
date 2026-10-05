# Coding Agent Instructions

## Read first

Read `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/TEST_PLAN.md`, and the implementation issue before editing code.

## Product decisions that are already settled

- This is a **standalone generic Android app optimizer**, not part of Nuvio.
- Modern Wireless ADB remains the Wi-Fi path. Issue #14 additionally authorizes same-device TCP ADB on current Ethernet addresses, port 5555, when firmware exposes it. Do not add physical USB/root transports, LAN scans, arbitrary hosts, or commands that enable `adb tcpip`.
- `minSdk` is Android 11 / API 30.
- Phones/tablets: Android 11+ if Wireless Debugging works.
- TV: intended baseline Android 13+, but always use runtime capability detection rather than version alone.
- Users can optimize any configured package, not only Nuvio.
- Package selection supports installed-app discovery, manual entry, and comma-separated bulk entry.
- Default compiler filter is `speed` using `cmd package compile -m speed -f`.
- No fake 0-100 compile percentage. Show real phases and x/y package progress.
- v1 includes an **Advanced ADB Console** for explicitly user-entered one-shot local shell commands. Keep this path isolated from built-in optimizer commands.
- Built-in optimizer behavior must remain typed/allow-listed; arbitrary shell text is accepted only in the Advanced console.
- External intents/public API must never execute arbitrary console commands.
- Never interpolate unvalidated user input into shell commands.
- External intents must never trigger silent optimization; explicit user confirmation is required.
- Keep the ADB key in Android-protected storage and never log private key material.

## Implementation style

- Kotlin + Jetpack Compose.
- Keep ADB transport, package repository, optimizer, persistence, and UI state behind interfaces so unit tests do not require a real device.
- Prefer small dependencies. Document any new dependency and its license in `docs/DEPENDENCIES.md`.
- Prefer deterministic state machines over ad-hoc callbacks.
- Keep built-in shell command construction centralized and allow-listed; keep raw console execution in a separate `AdbConsole` use case/interface.
- Treat OEM differences and unsupported commands as normal capability failures, not crashes.

## Required checks before a PR is complete

Run:

```bash
./scripts/check.sh
```

The PR must include/update tests for changed behavior. Do not disable lint/tests to make CI green.

## Token-efficient workflow

1. Read the spec once.
2. Inspect only files related to the current acceptance criterion.
3. Implement one vertical slice at a time.
4. Run targeted tests during work; run `scripts/check.*` before finalizing.
5. Summarize changed files, tests run, and any unresolved constraint. Avoid re-explaining the full product design in every update.
