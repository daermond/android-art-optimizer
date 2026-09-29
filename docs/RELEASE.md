# Releases

## CI

`.github/workflows/ci.yml` runs lint, JVM unit tests, and a debug APK build for pushes and pull requests.

## Signed releases

Tags matching `v*` trigger `.github/workflows/release.yml`.

Required GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow:

1. checks out source
2. restores Gradle cache
3. validates tests/lint
4. decodes the signing keystore only inside the runner
5. builds `assembleRelease`
6. calculates SHA-256
7. creates a GitHub Release with APK + checksum and generated release notes

Use `scripts/setup-github-signing.sh <path-to-keystore> <alias>` from an authenticated machine to configure the secrets interactively.

Use `scripts/create-release.sh 0.1.0` after `main` is clean and CI-ready to create/push tag `v0.1.0`.

Kadb includes the GPL-3.0 `spake2-java` pairing dependency. Review its redistribution requirements and the dependency record before publishing an APK.
