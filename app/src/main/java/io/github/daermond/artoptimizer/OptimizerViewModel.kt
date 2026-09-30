package io.github.daermond.artoptimizer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionPhase {
    NOT_PAIRED, SEARCHING_PAIR, PAIRING, SEARCHING_CONNECT, CONNECTING,
    CONNECTED, AUTH_FAILED, WRONG_DEVICE, UNAVAILABLE, ERROR
}

data class UiState(
    val connection: ConnectionPhase = ConnectionPhase.NOT_PAIRED,
    val detail: String = "",
    val profile: DeviceProfile? = null,
    val pairingEndpoints: List<MdnsEndpoint> = emptyList(),
    val connectEndpoints: List<MdnsEndpoint> = emptyList(),
    val discoveryStatus: MdnsStatus = MdnsStatus.STOPPED,
    val pairingSession: PairingSessionState = PairingSessionState(),
    val configured: List<PackageId> = emptyList(),
    val installed: List<PackageId> = emptyList(),
    val versions: Map<PackageId, InstalledPackage> = emptyMap(),
    val records: Map<PackageId, OptimizationRecord> = emptyMap(),
    val activeEvent: OptimizationEvent? = null,
    val running: Boolean = false,
    val entryFeedback: String = "",
    val compileAvailable: Boolean = false,
    val artAvailable: Boolean = false,
    val shellUid: String = "",
    val consoleWarningAccepted: Boolean = false,
    val consoleRunning: Boolean = false,
    val consoleStartedAt: Long = 0L,
    val consoleOutput: String = "",
    val consoleResult: String = "",
    val consoleHistory: List<String> = emptyList(),
)

class OptimizerViewModel(application: Application) : AndroidViewModel(application) {
    private val transport: DeviceTransport = WirelessAdbTransport(application)
    private val persistence = AppPersistence(application)
    private val optimizer = ArtOptimizer(transport)
    private val self = requireNotNull(PackageId.parse(application.packageName))
    private val mutable = MutableStateFlow(UiState(
        configured = persistence.packages(),
        records = persistence.records(),
        versions = persistence.seenVersions(),
        profile = persistence.profile(),
        consoleWarningAccepted = persistence.consoleWarningAccepted,
        consoleHistory = persistence.history(),
    ))
    val state: StateFlow<UiState> = mutable.asStateFlow()
    private var connectionJob: Job? = null
    private var optimizationJob: Job? = null
    private var consoleJob: Job? = null
    private var refreshJob: Job? = null
    private var capabilityJob: Job? = null

    init {
        transport.startDiscovery()
        viewModelScope.launch {
            transport.discovery.collect { found ->
                mutable.update { it.copy(
                    discoveryStatus = found.status,
                    pairingEndpoints = found.pairDevices.filter(transport::isLocalEndpoint),
                    connectEndpoints = found.connectDevices.filter(transport::isLocalEndpoint),
                ) }
            }
        }
        viewModelScope.launch {
            PairingSessionStore.state.collect { session ->
                mutable.update { it.copy(pairingSession = session) }
                if (session.status == PairingStatus.PAIRED) retry()
            }
        }
        if (persistence.paired && transport.hasCredential) retry()
        else mutable.update { it.copy(connection = ConnectionPhase.NOT_PAIRED) }
    }

    fun retry() {
        if (mutable.value.running || mutable.value.consoleRunning) return
        refreshJob?.cancel()
        capabilityJob?.cancel()
        transport.disconnect()
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            if (!persistence.paired || !transport.hasCredential) {
                mutable.update { it.copy(connection = ConnectionPhase.NOT_PAIRED) }
                return@launch
            }
            connectToDiscovered(persistence.profile())
        }
    }

    fun startPairing(mode: PairingMode) {
        runCatching { PairingService.start(getApplication(), mode) }
            .onFailure { mutable.update { it.copy(detail = "Could not start pairing service") } }
    }

    fun stopPairing() {
        runCatching { PairingService.stop(getApplication()) }
    }

    private suspend fun connectToDiscovered(saved: DeviceProfile?, preferredHost: String? = null) {
        mutable.update { it.copy(connection = ConnectionPhase.SEARCHING_CONNECT, detail = "Searching for Wireless Debugging") }
        val endpoints = withTimeoutOrNull(20_000) {
            transport.discovery.first { it.connectDevices.any(transport::isLocalEndpoint) }
                .connectDevices.filter(transport::isLocalEndpoint)
        }
        if (endpoints.isNullOrEmpty()) {
            mutable.update { it.copy(connection = ConnectionPhase.UNAVAILABLE,
                detail = "No modern Wireless Debugging endpoint found. Enable Wireless Debugging and retry.") }
            return
        }
        val ordered = endpoints.sortedBy { if (it.host == preferredHost) 0 else 1 }
        var wrongDevice = false
        for (endpoint in ordered) {
            mutable.update { it.copy(connection = ConnectionPhase.CONNECTING,
                detail = "Connecting to ${endpoint.name}") }
            try {
                val profile = transport.connect(endpoint)
                if (saved != null && !profile.matches(saved)) {
                    wrongDevice = true
                    transport.disconnect()
                    continue
                }
                persistence.saveProfile(profile)
                mutable.update { it.copy(connection = ConnectionPhase.CONNECTED, profile = profile,
                    detail = if (profile.strongIdentity) "Connected; device identity verified"
                    else "Connected; model-based identity check only") }
                probeCapabilities().join()
                refreshApps().join()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                transport.disconnect()
            }
        }
        mutable.update { it.copy(connection = if (wrongDevice) ConnectionPhase.WRONG_DEVICE else ConnectionPhase.AUTH_FAILED,
            detail = if (wrongDevice) "Discovered device did not match the saved profile"
                else "Could not authenticate. Retry or Pair Again if authorization was revoked.") }
    }

    fun pairAgain() {
        stopPairing()
        connectionJob?.cancel()
        optimizationJob?.cancel()
        consoleJob?.cancel()
        refreshJob?.cancel()
        capabilityJob?.cancel()
        try {
            transport.forgetCredential()
            persistence.paired = false
            persistence.saveProfile(null)
            PairingSessionStore.update(PairingSessionState())
        } catch (_: Exception) {
            mutable.update { it.copy(connection = ConnectionPhase.ERROR,
                detail = "Could not clear the pairing credential. Retry Pair Again.") }
            return
        }
        mutable.update { it.copy(connection = ConnectionPhase.NOT_PAIRED, profile = null,
            compileAvailable = false, artAvailable = false, shellUid = "", installed = emptyList(),
            detail = "Choose a pairing method below, then open the Wireless Debugging pairing-code dialog.") }
    }

    fun refreshApps(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            if (mutable.value.connection != ConnectionPhase.CONNECTED) return@launch
            try {
                val response = transport.shell(SafeAdbCommand.ListThirdPartyPackages)
                if (!response.succeeded) throw IllegalStateException()
                val discovered = PackageOutputParser.thirdPartyPackages(response.stdout)
                val installedConfigured = mutableListOf<PackageId>()
                val versions = buildMap {
                    for (id in mutable.value.configured) {
                        val path = transport.shell(SafeAdbCommand.PackagePath(id))
                        if (path.succeeded && path.stdout.contains("package:")) {
                            installedConfigured += id
                            val dump = transport.shell(SafeAdbCommand.PackageDump(id))
                            val (code, name) = PackageOutputParser.version(dump.stdout)
                            put(id, InstalledPackage(id, code, name))
                        }
                    }
                }
                persistence.saveSeenVersions(versions)
                mutable.update { current -> if (current.connection == ConnectionPhase.CONNECTED)
                    current.copy(installed = (discovered + installedConfigured).distinct(), versions = versions)
                    else current }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (lost: AdbConnectionException) {
                connectionLost(lost.message.orEmpty())
            } catch (_: Exception) {
                mutable.update { current -> if (current.connection == ConnectionPhase.CONNECTED)
                    current.copy(detail = "Connected, but package discovery failed. Retry refresh.") else current }
            }
        }.also { refreshJob = it }
    }

    private fun probeCapabilities() = viewModelScope.launch {
        try {
            val compile = transport.shell(SafeAdbCommand.CompileHelp)
            // OEMs may support dump while rejecting the undocumented `art help` sub-command.
            val art = transport.shell(SafeAdbCommand.ArtDump(self))
            val uid = transport.shell(SafeAdbCommand.ShellUid).stdout.trim()
            mutable.update { if (it.connection != ConnectionPhase.CONNECTED) it else it.copy(
                compileAvailable = CapabilityProbe.supportsCompile(compile),
                artAvailable = art.succeeded,
                shellUid = uid.take(20),
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lost: AdbConnectionException) {
            connectionLost(lost.message.orEmpty())
        }
    }.also { capabilityJob = it }

    private fun connectionLost(message: String) {
        mutable.update { it.copy(connection = ConnectionPhase.UNAVAILABLE, compileAvailable = false,
            artAvailable = false, shellUid = "", detail = message) }
    }

    fun addPackages(raw: String) {
        val parsed = PackageIdParser.parseCsv(raw)
        val combined = (mutable.value.configured + parsed.valid).distinct()
        persistence.savePackages(combined)
        mutable.update { it.copy(configured = combined,
            entryFeedback = if (parsed.invalid.isEmpty()) "Added ${parsed.valid.size} valid package(s)"
                else "Rejected: ${parsed.invalid.joinToString().take(180)}") }
        refreshApps()
    }

    fun removePackage(id: PackageId) {
        val remaining = mutable.value.configured - id
        persistence.savePackages(remaining)
        mutable.update { it.copy(configured = remaining) }
    }

    fun optimize(ids: List<PackageId>) {
        if (mutable.value.connection != ConnectionPhase.CONNECTED || !mutable.value.compileAvailable ||
            mutable.value.running || mutable.value.consoleRunning) return
        mutable.update { it.copy(running = true, activeEvent = null) }
        optimizationJob = viewModelScope.launch {
            try {
                optimizer.optimize(ids, self).collect { event ->
                    mutable.update { current ->
                        val latest = terminalRecord(current.records[event.packageId], event)
                        val records = if (latest != null)
                            current.records + (event.packageId to latest) else current.records
                        if (latest != null) persistence.saveRecords(records)
                        current.copy(activeEvent = event, records = records)
                    }
                    if (event.connectionLost) connectionLost(event.message)
                }
                refreshApps()
            } finally {
                mutable.update { it.copy(running = false) }
            }
        }
    }

    fun acceptConsoleWarning() {
        persistence.consoleWarningAccepted = true
        mutable.update { it.copy(consoleWarningAccepted = true) }
    }

    fun runConsole(raw: String) {
        if (!mutable.value.consoleWarningAccepted || mutable.value.connection != ConnectionPhase.CONNECTED ||
            mutable.value.consoleRunning || mutable.value.running) return
        val command = ConsoleCommand.normalize(raw)
        if (command.startsWith("adb ", ignoreCase = true)) {
            mutable.update { it.copy(consoleResult = "Only adb shell commands are supported") }
            return
        }
        if (command.isBlank() || command.length > 2_000) {
            mutable.update { it.copy(consoleResult = "Enter a command of at most 2,000 characters") }
            return
        }
        persistence.addHistory(command)
        mutable.update { it.copy(consoleRunning = true, consoleStartedAt = System.currentTimeMillis(),
            consoleOutput = "", consoleResult = "Running", consoleHistory = persistence.history()) }
        consoleJob = viewModelScope.launch {
            try {
                val response = transport.shellRaw(command)
                val output = buildString {
                    if (response.stdout.isNotEmpty()) append("stdout:\n").append(response.stdout.take(64_000))
                    if (response.stderr.isNotEmpty()) append("\nstderr:\n").append(response.stderr.take(16_000))
                }.take(80_000)
                mutable.update { it.copy(consoleOutput = output,
                    consoleResult = if (response.truncated) "Output limit reached; command stopped"
                        else response.exitCode?.let { code -> "Exit code: $code" } ?: "Exit code unavailable") }
            } catch (_: CancellationException) {
                mutable.update { it.copy(consoleResult = "Cancelled") }
            } catch (lost: AdbConnectionException) {
                connectionLost(lost.message.orEmpty())
                mutable.update { it.copy(consoleResult = lost.message.orEmpty()) }
            } catch (_: Exception) {
                mutable.update { it.copy(consoleResult = "Command failed or timed out") }
            } finally {
                mutable.update { it.copy(consoleRunning = false) }
            }
        }
    }

    fun cancelConsole() {
        transport.cancelRaw()
        consoleJob?.cancel()
        connectionLost("Console cancelled. Retry the connection before running another command.")
    }
    fun clearConsoleOutput() { mutable.update { it.copy(consoleOutput = "", consoleResult = "") } }
    fun clearConsoleHistory() {
        persistence.clearHistory()
        mutable.update { it.copy(consoleHistory = emptyList()) }
    }

    override fun onCleared() {
        transport.close()
        super.onCleared()
    }
}
