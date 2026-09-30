# Architecture

## Recommended stack

- Kotlin
- Jetpack Compose + Material 3
- Coroutines / Flow
- ViewModel
- DataStore for small persistent state unless implementation evidence justifies Room
- Modern ADB client library that supports Android-hosted TLS pairing, mDNS discovery, and shell execution (evaluate Kadb first; record chosen dependency/license)

## Layers

```text
UI / ViewModels
      |
Use cases / state machines
      |
+----------------------+---------------------+
|                      |                     |
DeviceConnection       AppRepository         ArtOptimizer
|                      |                     |
AdbTransport            PackageCommandApi     ArtCommandApi
|                                            |
WirelessAdbTransport (mDNS + TLS pairing + stored key)
```

## Core interfaces

Suggested boundaries; names may change but responsibilities should not blur.

```kotlin
interface AdbTransport {
    suspend fun discoverPairingEndpoints(): List<AdbEndpoint>
    suspend fun pair(endpoint: AdbEndpoint, code: String): PairingResult
    suspend fun discoverConnectEndpoints(): List<AdbEndpoint>
    suspend fun connect(endpoint: AdbEndpoint): ConnectionResult
    suspend fun shell(command: SafeAdbCommand): ShellResult
    suspend fun shellRaw(command: String): ShellResult // Advanced console only
    suspend fun disconnect()
}

interface PackageRepository {
    suspend fun listThirdPartyPackages(): List<InstalledPackage>
    suspend fun inspect(packageId: PackageId): PackageInspection
}

interface ArtOptimizer {
    fun optimize(packages: List<PackageId>): Flow<OptimizationEvent>
}
```

## Command safety

Represent commands as typed objects rather than raw strings originating in UI state.

```kotlin
sealed interface SafeAdbCommand {
    data class PackagePath(val packageId: PackageId) : SafeAdbCommand
    data class ForceStop(val packageId: PackageId) : SafeAdbCommand
    data class CompileSpeed(val packageId: PackageId) : SafeAdbCommand
    data class ArtDump(val packageId: PackageId) : SafeAdbCommand
    data object ListThirdPartyPackages : SafeAdbCommand
}
```

Only the command renderer converts typed commands to shell strings for built-in application behavior.

### Raw console exception

The Advanced ADB Console is the sole exception to typed commands. Keep it behind a separate interface/use case so arbitrary text cannot accidentally flow into optimizer operations:

```kotlin
interface AdbConsole {
    fun execute(command: String): Flow<ConsoleEvent>
    suspend fun cancel()
}

sealed interface ConsoleEvent {
    data class Started(val command: String) : ConsoleEvent
    data class Stdout(val text: String) : ConsoleEvent
    data class Stderr(val text: String) : ConsoleEvent
    data class Completed(val exitCode: Int?) : ConsoleEvent
    data class Failed(val message: String) : ConsoleEvent
}
```

`AdbConsole` may delegate to `AdbTransport.shellRaw`, but no other feature may accept a raw command string. Optionally normalize a leading `adb shell ` prefix in the console use case. Do not treat host-side ADB subcommands as shell commands.

Retain only bounded console output/history. Console history belongs to local app persistence and should be independently clearable.

## Connection state machine

```text
Unconfigured
  -> DiscoveringPairEndpoint
  -> Pairing
  -> DiscoveringConnectEndpoint
  -> Connecting
  -> VerifyingIdentity
  -> Connected

Previously paired startup:
Configured
  -> DiscoveringConnectEndpoint
  -> Connecting
  -> VerifyingIdentity
  -> Connected

Failures are states with Retry/Pair Again transitions; pairing data is not deleted automatically.

The transport serializes connection setup, built-in shell commands, and console commands. Socket reads have no short idle timeout: a quiet ART compilation may take longer than 15 seconds. A cancellable operation deadline closes an established socket to unblock reads (five minutes for compilation; 30 seconds for setup, other commands, and console). Kadb 2.1.4 does not expose an in-progress handshake socket to `close`, so a five-minute socket-read fallback bounds that library limitation. Cancelled sessions are checked before shell execution. Transport failures discard the connection, switch the UI to Retry, and stop the current optimization batch. A command is never automatically replayed after an uncertain result. Cancelling the console also closes its connection and requires Retry.

Pairing input is handled by a short-lived `connectedDevice` foreground service so Android Settings can remain visible. It owns mDNS discovery and the pairing attempt; the ViewModel observes session state and reconnects after the service stores a successful pairing. Notification `RemoteInput` and a QR-linked local HTTP server are two front ends to the same pairing operation. The HTTP server accepts only six-digit codes on a random session path, checks Host and rejects foreign Origin headers when supplied (some mobile browsers omit them), and has bounded requests, submissions, and lifetime. Neither input path accepts ADB shell text.
```

## Optimization state machine

```text
Queued
 -> ValidatingPackage
 -> InspectingBefore
 -> Stopping
 -> Compiling
 -> InspectingAfter
 -> Completed

Any stage -> Failed(reason)
Missing package -> Skipped(NotInstalled)
```

Batch progress is derived from terminal package states. The Compiling phase is indeterminate with elapsed time.

## Device identity

Persist a device profile after pairing. Prefer a stable shell-visible identifier when available, but design for its absence. Separate `IdentityConfidence` (Strong/Weak) from connection success.

## Capability probing

Build a `DeviceCapabilities` record after connecting. Probe features such as package compilation and ART-state inspection rather than scattering SDK checks across UI/business logic.

## Dependency rule

ADB-library-specific classes must live only under the transport implementation. Domain/UI tests use fakes.

## Console execution state

```text
Idle
 -> Running(command, elapsed)
 -> Completed(exitCode?, output)

Running -> Cancelled
Running -> Failed(reason)
```

The UI may stream stdout/stderr if the ADB library supports it; otherwise publish output at command completion. Never fabricate a numeric exit code or separated stderr when the underlying transport cannot supply them.
