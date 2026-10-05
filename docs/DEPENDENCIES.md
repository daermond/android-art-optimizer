# Dependency Record

Keep this file updated when adding non-AndroidX dependencies.

| Dependency | Purpose | License | Notes |
|---|---|---|---|
| [Kadb 2.1.4](https://github.com/flyfishxu/Kadb) | Android-hosted TLS pairing and shell | Apache-2.0 | Private key is kept in app-private no-backup storage; validated on the tablet and TV in TEST_PLAN.md. |
| [Kadb mDNS 2.1.4](https://github.com/flyfishxu/Kadb) | Discover modern Wireless ADB endpoints with Android NSD | Apache-2.0 | Only `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` are used. |
| kotlinx-coroutines-android 1.9.0 | Async connection and command execution | Apache-2.0 | Android dispatcher for UI-facing state. |
| [spake2-java 1.1.1](https://github.com/Flyfish233/spake2-java) | Kadb pairing transitive dependency | GPL-3.0 | Resolved from JitPack, restricted to its group. App is GPLv3; matching sources are included with releases. |
| ed25519-elisabeth / curve25519-elisabeth 0.1.0 | Pairing cryptography, transitive dependencies | MIT | Source archives and upstream attribution are included with releases. |
| [ZXing core 3.5.4](https://github.com/zxing/zxing) | Render a QR code for the local pairing page | Apache-2.0 | QR generation is local; no scan or cloud service. |

See [third-party notices](../THIRD_PARTY_NOTICES.md) and the licenses bundled in the APK. Release helper scripts use Python's standard library and add no app dependency.
