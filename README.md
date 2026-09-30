# Android ART Optimizer

Optimize selected Android apps using Android's own ART compiler. Pair with Wireless Debugging on the same device, choose your apps, and run optimization from a phone, tablet, or TV remote.

## What it does

- Optimizes Java/Kotlin code in selected apps using Android's `speed` compilation mode.
- Lets you choose installed apps, enter a package ID, or paste a comma-separated list.
- Offers two pairing methods on every device: notification reply or a QR-linked web page on a second device.
- Shows actual phases, elapsed compilation time, and completed-app counts.
- Verifies ART compiler state when available and clearly labels command-only validation otherwise.
- Remembers your app list and results, reconnects on launch, and flags app version changes.
- Includes an optional Advanced ADB Console for commands you explicitly enter.

Benefits depend on the app and Android's existing compilation state. This targets managed app code; it does not optimize native libraries, graphics, video decoding, or network performance. The selected app is stopped during optimization and can be opened again afterward.

## Supported devices

| Device | Requirements |
|---|---|
| Phone or tablet | Android 11 or newer with **Wireless Debugging** |
| Android TV / Google TV | Modern **Wireless Debugging**; Android 13+ is the intended baseline |

The app checks actual command support. An Android version alone does not guarantee that an OEM exposes the required features. Root is not required, and normal use does not need a computer.

Tested on a Samsung tablet with Android 16 and a TCL Smart TV Pro with Android 14. See the [test record](docs/TEST_PLAN.md) for details.

## Install

1. Open [GitHub Releases](https://github.com/daermond/android-art-optimizer/releases).
2. Download the **`.apk`** attachment. The same APK is used on phones, tablets, and TVs.
3. Open it on your Android device. If prompted, allow that browser or file manager to **install unknown apps**, then install.
4. On TV, transfer the APK using a USB drive or a trusted file-transfer method, then open it with the TV's file manager. Look for **ART Optimizer** in the TV launcher.

The checksum and source archive are separate attachments; only the APK is needed to install. This repository is currently private, so GitHub access is required to download its releases.

**Moving from an early test build:** the production release uses a permanent signing key. Android cannot install it over the debug-signed test build. Note your configured package IDs, uninstall the test build, then install the release and pair again. Future official release updates use the same key and can update the previous release in place.

## First use

1. Enable **Developer Options** and **Wireless Debugging** in Android Settings.
2. Open ART Optimizer and choose a pairing method.
3. In Settings, open **Wireless Debugging → Pair device with pairing code** and leave the code dialog open.
4. Enter the code through the chosen method:
   - **Enter code in notification:** reply to the optimizer's notification while the Settings dialog remains open. Allow notifications if asked.
   - **Enter code on another device:** scan the QR code with another phone, tablet, or computer on the same local network and enter the Settings code on that page. An iPhone can use the page too.
5. Wait for **Connected**, then open **Apps**.
6. Add an installed app or enter its package ID, then select **Optimize**. **Optimize All eligible** processes your configured installed apps one at a time.

On TV, use the D-pad and Select. Text entry starts only after selecting **Edit**. **Open Developer Options** provides a shortcut to Settings.

Keep Android's code dialog open until pairing completes. The QR page uses temporary HTTP on the local network; use a trusted network. A phone using this method needs a second device. See the [user guide](docs/USER_GUIDE.md) for detailed steps and troubleshooting.

## Results and recovery

| Result | Meaning |
|---|---|
| Success / ART_STATE | Compilation succeeded and Android reported the `speed` compiler state. |
| Success / COMMAND | Compilation succeeded, but full ART-state inspection was unavailable. |
| Updated since optimization | The installed app version changed; optimize again if you want to compile the new version. |
| Connection unavailable | Enable Wireless Debugging and select **Retry**. Use **Pair Again** if Android revoked authorization. |

Compilation has a five-minute limit. Other shell commands, including the console, normally have a 30-second limit. An interrupted compile is not repeated automatically because its result may be unknown. Cancelling the console closes its connection; select Retry afterward.

## Help and project information

- [Detailed user guide](docs/USER_GUIDE.md)
- [Report a problem](https://github.com/daermond/android-art-optimizer/issues)
- [Build and contribute](docs/CONTRIBUTING.md)
- [Release maintenance](docs/RELEASE.md)
- [Dependency licenses](docs/DEPENDENCIES.md)

Licensed under [GPLv3](LICENSE). Releases include the matching source archive and [third-party notices](THIRD_PARTY_NOTICES.md).

When reporting a problem, include the device model, Android version, Diagnostics state, and displayed error. Do not share pairing codes or private credentials.
