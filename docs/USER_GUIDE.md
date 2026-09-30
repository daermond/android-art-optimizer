# User guide

## Before you start

ART Optimizer asks Android to compile managed code in apps you choose. Benefits vary because some apps are already well compiled by Android. Save work in the target app first: optimization stops it before compilation.

You need Android 11+ and modern **Wireless Debugging**. TV support depends on the manufacturer; Android 13+ is the intended baseline. Actual commands are detected after connecting.

## Installation and updates

Download the APK from [GitHub Releases](https://github.com/daermond/android-art-optimizer/releases). The same APK works on phones, tablets, and supported TVs. The source ZIP and SHA-256 checksum are optional downloads for inspecting or verifying a release.

On a phone/tablet, open the APK and grant your browser or file manager permission to install unknown apps when requested. On TV, transfer it using a USB drive or trusted file-transfer method and open it in a file manager. Look for **ART Optimizer** in the TV launcher.

Later official release APKs can update the existing official release while retaining settings and results. An early debug-signed test build uses a different certificate: note your package IDs, uninstall that build, install the official release, and pair again. Uninstalling deletes the optimizer's local data.

The repository is currently private. Downloading its releases requires a GitHub account with access.

## Enable Wireless Debugging

1. In Settings → About, find **Build number** or **Android TV OS build**.
2. If Developer Options is hidden, select that entry seven times and follow Android's prompts. Menu names vary by manufacturer.
3. Open **Developer Options**, enable **Wireless Debugging**, and allow debugging on the current trusted network.
4. Leave Wireless Debugging enabled while using the optimizer. It discovers changing ports automatically.

A device that has only USB debugging and no modern Wireless Debugging pairing screen cannot use this app's current connection method.

## Pair through a notification

1. In the optimizer, choose **Enter code in notification** and allow notifications if prompted.
2. Open Settings → Wireless Debugging → **Pair device with pairing code**.
3. Leave the code dialog open. Pull down the notification shade, open the optimizer's reply action, enter the six-digit Settings code, and send it.
4. Return to the optimizer and wait for **Connected**.

This needs a usable notification shade. If unavailable, choose the web method. Some devices hide the pairing endpoint when Settings loses focus; keep its code dialog visible. Split screen may help when supported.

## Pair through the QR code and web page

1. Choose **Enter code on another device** in the optimizer.
2. Scan its QR code with another phone, tablet, or computer on the same local network, or manually open the displayed URL. An iPhone uses its browser and needs no companion Android app.
3. On the Android device being paired, open Wireless Debugging → **Pair device with pairing code**. On TV, **Open Developer Options** is a shortcut to Settings.
4. Leave the Settings code dialog open and enter its code on the second device's page.
5. Submit the code and return to the optimizer. Successful pairing closes the temporary web service and connects the app.

The page lasts three minutes and permits three submissions. **Cancel pairing** closes it sooner. HTTP does not hide the code from devices that can intercept local traffic; use a trusted network and avoid sharing the URL or code.

If the page will not load, check the network on both devices. Guest networks, VPNs, and router client-isolation settings may prevent access.

## Choose apps and optimize

Open **Apps** after connecting. Choose **Add** beside a discovered third-party package, enter one package ID such as `app.smarttube`, or enter several IDs separated by commas. A package ID may differ from the app's displayed name. Invalid IDs are rejected.

On TV, select **Edit Package ID or comma-separated list**, enter IDs, confirm **Done**, then select **Add packages**. Use **Filter packages** to narrow the discovered list.

Choose **Optimize** on one app or **Optimize All eligible** for installed configured apps. The optimizer validates each package, reads its version, stops it, compiles it, and checks the result. It cannot optimize itself. Missing packages stay in your list until you remove them.

Progress shows real phases, elapsed compilation time, and completed-app counts. A batch runs one app at a time. Open optimized apps normally afterward.

## Results

| Result | Meaning |
|---|---|
| Success, ART_STATE | Compilation succeeded and ART inspection reported `speed`. |
| Success, COMMAND | Compilation succeeded; full compiler-state inspection was unavailable. |
| Failed | Read the displayed reason and correct the problem before trying again. |
| Skipped | The package was missing or was the optimizer itself. |
| Updated since optimization | The app version changed after a successful run. Optimization never runs automatically on update. |

Old results remain until another attempt updates them. **Refresh** updates installed-package/version information.

## TV remote controls

Use the D-pad; a yellow outline shows focus. Select activates a control, and long pages scroll to it. Passing an **Edit** control does not open the keyboard. Select Edit to type, then use the keyboard's confirmation action or **Done** to save. Back dismisses the keyboard, then the editor, and returns to the page.

Both pairing methods are offered on TV. If its notification shade cannot accept replies, use QR/web pairing from another device.

## Troubleshooting

| Problem | What to try |
|---|---|
| Not paired | Start a pairing method and use a fresh Settings code. |
| Searching or unavailable | Enable Wireless Debugging, check the network, and select **Retry**. |
| Authentication rejected | Retry first. If Android removed authorization, use **Pair Again**. |
| Compile command unavailable | Check Diagnostics; the OEM's Android build may not expose compilation. |
| ART inspection unavailable | Compilation can still work with command-level validation. |
| Timed out or interrupted | Retry the connection, then explicitly retry the operation. An interrupted compile may have an unknown result. |
| Wrong device | The endpoint did not match the saved identity; check Settings on the device you are using. |

Retry keeps pairing and configured apps. Pair Again clears the credential and saved device profile so you can authorize it again; it keeps your configured app list.

## Advanced ADB Console

This optional screen runs shell commands you explicitly enter on the paired device. Its warning explains that commands can change settings or packages. Select **Run** to execute; opening the screen or selecting history does not execute a command.

Output and exit status appear after completion. You can select/copy output, clear it, and clear the bounded local history. A leading `adb shell` prefix is accepted. PC-side commands such as `adb install` are outside the console's scope.

The console normally stops at 30 seconds. **Cancel** closes its connection to interrupt blocked reads; use Retry afterward. Run only commands you understand.

## Local data and support

The app stores its credential, device profile, configured apps, results, and console history locally. Pairing and built-in commands operate on the same device. The QR page is temporary and accepts pairing codes, not shell commands.

For help, use [GitHub Issues](https://github.com/daermond/android-art-optimizer/issues). Include the model, Android version, Diagnostics state, and exact error. Do not include pairing codes or private credentials.
