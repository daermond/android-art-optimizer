# Build and contribute

Installation and operation are documented in the [README](../README.md) and [user guide](USER_GUIDE.md).

## Development setup

The project uses Kotlin, Compose, the committed Gradle 9.3.1 wrapper, Android Gradle Plugin 9.1.1, compile SDK 37, and target SDK 35. Use JDK 17 and an Android SDK, with `ANDROID_HOME` or a Git-ignored `local.properties` pointing to it. The minimum runtime is API 30.

Run `scripts/check.ps1` on Windows or `scripts/check.sh` on Linux/macOS for lint, JVM tests, and debug APK assembly. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Read [AGENTS.md](../AGENTS.md), [SPEC.md](SPEC.md), [ARCHITECTURE.md](ARCHITECTURE.md), [TEST_PLAN.md](TEST_PLAN.md), and [issue #1](https://github.com/daermond/android-art-optimizer/issues/1) before changing behavior. Work in a branch, preserve typed/allow-listed built-in commands, add regression tests, and submit a PR with validation evidence.

Credentials, SDK configuration, APKs, and runtime artifacts do not belong in Git. Keep dependency records and device-test evidence current. See [RELEASE.md](RELEASE.md) for signing and publication.
