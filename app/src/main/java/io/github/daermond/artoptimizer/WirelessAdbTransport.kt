package io.github.daermond.artoptimizer

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: String,
    val stableId: String?,
) {
    val displayName: String get() = "$manufacturer $model (Android $release)"
    val strongIdentity: Boolean get() = stableId != null

    fun matches(saved: DeviceProfile): Boolean =
        if (stableId != null && saved.stableId != null) stableId == saved.stableId
        else manufacturer == saved.manufacturer && model == saved.model && sdk == saved.sdk
}

interface DeviceTransport : SafeShell, ConsoleShell, AutoCloseable {
    val discovery: StateFlow<MdnsDiscoveryState>
    val network: StateFlow<LocalNetworkState>
    val hasCredential: Boolean
    fun isLocalEndpoint(endpoint: MdnsEndpoint): Boolean
    fun startDiscovery()
    suspend fun pair(endpoint: MdnsEndpoint, code: String)
    suspend fun connect(endpoint: LocalAdbEndpoint): DeviceProfile
    fun disconnect()
    fun cancelRaw()
    fun forgetCredential()
}

/** Kadb is kept behind this boundary; no UI text enters SafeCommandRenderer. */
class WirelessAdbTransport(context: Context) : DeviceTransport {
    private val appContext = context.applicationContext
    private val keyFile = File(appContext.noBackupFilesDir, "adb-host-key.pem")
    private val networks: LocalNetworkMonitor = AndroidLocalNetworkMonitor(appContext)
    private val mdns = KadbMdnsAndroid(appContext,
        MdnsConfig(serviceTypes = setOf(MdnsServiceType.TLS_PAIRING, MdnsServiceType.TLS_CONNECT)))
    private val client = AtomicReference<Kadb?>()
    private val operations = AdbOperationRunner()

    init {
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
            policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
        )
    }

    override val discovery: StateFlow<MdnsDiscoveryState> get() = mdns.state
    override val network: StateFlow<LocalNetworkState> get() = networks.state
    override val hasCredential: Boolean get() = keyFile.isFile && keyFile.length() > 0L

    override fun isLocalEndpoint(endpoint: MdnsEndpoint): Boolean =
        LocalConnectionPolicy.isLocal(endpoint.host, network.value)

    override fun startDiscovery() = mdns.start()

    override suspend fun pair(endpoint: MdnsEndpoint, code: String) = withContext(Dispatchers.IO) {
        require(endpoint.serviceType == MdnsServiceType.TLS_PAIRING)
        require(isLocalEndpoint(endpoint)) { "Endpoint is not on this device" }
        require(Regex("^[0-9]{6}$").matches(code))
        KadbCert.ensureReady()
        Kadb.pair(endpoint.host, endpoint.port, code)
    }

    override suspend fun connect(endpoint: LocalAdbEndpoint): DeviceProfile = operations.run(30_000, ::disconnect) {
        require(LocalConnectionPolicy.permits(endpoint, network.value)) { "Endpoint is not on this device's current network" }
        if (endpoint.kind == AdbConnectionKind.TLS) {
            require(discovery.value.connectDevices.any {
                it.serviceType == MdnsServiceType.TLS_CONNECT && it.host == endpoint.host && it.port == endpoint.port
            }) { "Wireless Debugging endpoint is no longer advertised" }
            require(hasCredential) { "Pair this device first" }
        } else {
            // Standard ADB authenticates this same app-owned RSA key via Android's authorization UI.
            KadbCert.ensureReady()
        }
        val connectionNetwork = network.value
        disconnect()
        // Keep a long fallback bound as Kadb cannot close a handshake before it publishes its socket.
        // Shorter operation deadlines below close an established transport, including quiet reads.
        val next = Kadb.create(endpoint.host, endpoint.port, connectTimeout = 5_000, socketTimeout = 300_000)
        client.set(next)
        try {
            fun requireCurrent() {
                check(client.get() === next && network.value == connectionNetwork) { "Connection setup was cancelled" }
            }
            requireCurrent()
            val probe = next.shell(SafeCommandRenderer.render(SafeAdbCommand.Probe))
            check(probe.exitCode == 0 && probe.output.trim() == "art-optimizer-ready") {
                "ADB shell probe failed"
            }
            fun prop(command: SafeAdbCommand): String {
                requireCurrent()
                return next.shell(SafeCommandRenderer.render(command)).output.trim().take(120)
            }
            val id = prop(SafeAdbCommand.DeviceId).takeUnless {
                it.isBlank() || it == "null" || it == "unknown"
            }
            val profile = DeviceProfile(
                prop(SafeAdbCommand.Manufacturer),
                prop(SafeAdbCommand.Model),
                prop(SafeAdbCommand.AndroidRelease),
                prop(SafeAdbCommand.Sdk),
                id,
            )
            requireCurrent()
            profile
        } catch (error: Exception) {
            next.close()
            throw error
        }
    }

    override suspend fun shell(command: SafeAdbCommand): ShellResult = operations.run(
        if (command is SafeAdbCommand.CompileSpeed) 300_000 else 30_000, ::disconnect,
    ) {
        execute(SafeCommandRenderer.render(command))
    }

    override suspend fun shellRaw(command: String): ShellResult = operations.run(30_000, ::disconnect) {
        val connected = client.get() ?: throw IllegalStateException("Connect to ADB first")
        check(connected.supportsFeature("shell_v2")) { "Console requires shell v2 for bounded output" }
        if (client.get() !== connected) {
            connected.close()
            throw IllegalStateException("Connection was cancelled")
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val limit = 80_000
        connected.openShell(command).use { stream ->
            while (true) {
                val packet = stream.read()
                if (packet is AdbShellPacket.Exit) {
                    return@run ShellResult(stdout.toString(), stderr.toString(),
                        packet.payload[0].toUByte().toInt())
                }
                val destination = if (packet is AdbShellPacket.StdError) stderr else stdout
                val remaining = limit - stdout.length - stderr.length
                if (remaining <= 0) {
                    return@run ShellResult(stdout.toString(), stderr.toString(), null, truncated = true)
                }
                val chunk = String(packet.payload)
                destination.append(chunk.take(remaining))
                if (chunk.length > remaining) {
                    return@run ShellResult(stdout.toString(), stderr.toString(), null, truncated = true)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ShellResult(stdout.toString(), stderr.toString(), null)
        }
    }

    private fun execute(command: String): ShellResult {
        val connected = client.get() ?: throw IllegalStateException("Connect to ADB first")
        val exitCodeAvailable = connected.supportsFeature("shell_v2")
        if (client.get() !== connected) {
            connected.close()
            throw IllegalStateException("Connection was cancelled")
        }
        val response = connected.shell(command)
        return ShellResult(response.output, response.errorOutput,
            if (exitCodeAvailable) response.exitCode else null)
    }

    override fun disconnect() {
        client.getAndSet(null)?.close()
    }

    override fun cancelRaw() {
        // Closing the socket also wakes a console read that has not received a packet yet.
        disconnect()
    }

    override fun forgetCredential() {
        disconnect()
        KadbCert.clear()
    }

    override fun close() {
        disconnect()
        mdns.close()
        networks.close()
    }
}
