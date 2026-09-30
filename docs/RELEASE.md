# Release maintenance

For installing or using the app, see the [README](../README.md) and [user guide](USER_GUIDE.md).

## One-time signing setup on Windows

The permanent signing key is needed for future Android updates. Keep it; never regenerate it for a new release.

```powershell
.\scripts\create-signing-key.ps1
.\scripts\build-signed-release.ps1 0.1.0
.\scripts\setup-github-signing.ps1 -Keystore .\secrets\android-art-optimizer-release.p12 -Alias art-optimizer -PasswordFile .\secrets\signing-password.dpapi
```

The first script creates an ignored PKCS#12 key and a Windows DPAPI-encrypted password file under `secrets/`. The password file can be decrypted only by the same Windows account. Both files must be retained. For an independent backup, export the password using a trusted password manager and keep it with a secure backup of the key; copying only the DPAPI file to another machine is insufficient. Never commit these files or print credentials into logs.

Uploading secrets requires the repository owner's authorization. The upload helper stores four GitHub Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Without `-PasswordFile`, it prompts securely for passwords. Linux/macOS can use `scripts/setup-github-signing.sh` with an existing signing key.

The first production certificate SHA-256 fingerprint is:

```text
c3c5413421bb16fc53125858f5e77456038f891c19e558623b3221404bb574ac
```

Early debug builds use a different certificate. Users must uninstall those before installing the official release and will need to configure/pair again. Do not silently uninstall their app.

## Prepare a version

1. Update user documentation and create `docs/releases/vX.Y.Z.md` with features, installation advice, and known limitations.
2. Run `scripts/check.ps1` (or `scripts/check.sh`) and `scripts/build-signed-release.ps1 X.Y.Z` for the signed, minified candidate.
3. Verify the APK with Android SDK `apksigner verify --verbose --print-certs`. Preserve the permanent certificate.
4. Run `python -m unittest discover -s scripts/tests` for release-helper regression tests.
5. Commit/push a PR, wait for CI, and merge it to `main`.

Versions use `major.minor.patch`. Minor and patch must be 0–999. Android version code is `major * 1000000 + minor * 1000 + patch`: v0.1.0 is 1000, v0.1.1 is 1001, and v1.0.0 is 1000000. A workflow rerun keeps the same code. For custom builds, set `VERSION_NAME` and `VERSION_CODE` before invoking Gradle; use your own signing key or build a debug APK.

## Publish

```powershell
.\scripts\create-release.ps1 0.1.0
```

Or run `scripts/create-release.sh 0.1.0` on Linux/macOS with Python 3 and GitHub CLI. The helpers require a clean checkout, matching local/remote main, passing main CI, signing-secret names, a license, release notes, and a new tag. They stop on command errors before proceeding.

The tag-triggered workflow runs lint and JVM tests, builds a signed release APK, verifies its signature, and creates a source ZIP from the exact tracked commit. Pinned pairing/cryptography source downloads are checked against committed SHA-256 values. Local credentials and SDK configuration are excluded.

The workflow creates a draft release, uploads the APK, source ZIP, and `SHA256SUMS.txt`, downloads the assets to verify their hashes, then publishes. A failure leaves an unpublished draft for inspection. A rerun can replace draft assets; it refuses to replace a published release. After publication, verify downloaded hashes, certificate, package ID, and version. Repository visibility stays unchanged; private releases require repository access.

## Source and licenses

The app is GPLv3. [Third-party notices](../THIRD_PARTY_NOTICES.md) describe dependency licenses. Each release's source ZIP includes the matching application source/build scripts and the pinned Kadb, spake2-java, and Elisabeth sources; its manifest identifies the commit and dependency hashes. Do not remove the source attachment while distributing its APK.
