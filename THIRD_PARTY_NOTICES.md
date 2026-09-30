# License and third-party notices

Android ART Optimizer is licensed under GNU GPL version 3 (GPL-3.0-only).
See [LICENSE](LICENSE) for the complete terms. There is no warranty.

The APK includes these projects and their transitive runtime components:

| Project | License | Upstream |
|---|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| Kadb and Kadb mDNS 2.1.4 | Apache-2.0 | https://github.com/flyfishxu/Kadb |
| Okio | Apache-2.0 | https://github.com/square/okio |
| ZXing core 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| spake2-java 1.1.1 | GPL-3.0 | https://github.com/Flyfish233/spake2-java |
| ed25519-elisabeth / curve25519-elisabeth 0.1.0 | MIT, with the upstream BSD attribution | https://github.com/cryptography-cafe/ed25519-elisabeth, https://github.com/cryptography-cafe/curve25519-elisabeth |

The original licenses and attribution are retained in upstream source. Full
Apache-2.0, GPL-3.0, and Elisabeth license notices are also included in the APK's
`assets/licenses/` directory. Dependencies retain their respective licenses.

Each release provides `android-art-optimizer-vX.Y.Z-source.zip` beside the APK.
It includes the matching application source, Gradle build scripts and wrapper,
and pinned Kadb, spake2-java, and Elisabeth source archives. `SOURCE_MANIFEST.json`
records the application commit and dependency URLs/checksums. The remaining
dependencies are fetched from the public repositories in the Gradle build files.
See `docs/CONTRIBUTING.md` to build, and `docs/RELEASE.md` for the version settings.

Signing credentials are excluded. You can build and sign a modified version with
your own key; Android requires uninstalling a differently signed version first.
