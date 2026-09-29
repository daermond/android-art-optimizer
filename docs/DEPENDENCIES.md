# Dependency Record

Keep this file updated when adding non-AndroidX dependencies.

| Dependency | Purpose | License | Notes |
|---|---|---|---|
| [Kadb 2.1.4](https://github.com/flyfishxu/Kadb) | Android-hosted TLS pairing and shell | Apache-2.0 | Private key is kept in app-private no-backup storage; runtime pairing and same-device discovery still need device validation. |
| [Kadb mDNS 2.1.4](https://github.com/flyfishxu/Kadb) | Discover modern Wireless ADB endpoints with Android NSD | Apache-2.0 | Only `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` are used. |
| kotlinx-coroutines-android 1.9.0 | Async connection and command execution | Apache-2.0 | Android dispatcher for UI-facing state. |
| [spake2-java 1.1.1](https://github.com/Flyfish233/spake2-java) | Kadb pairing transitive dependency | GPL-3.0 | Resolved from JitPack, restricted to its group. Review redistribution obligations before publishing an APK. |
