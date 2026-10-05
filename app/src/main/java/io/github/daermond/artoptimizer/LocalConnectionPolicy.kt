package io.github.daermond.artoptimizer

import java.net.InetAddress

enum class LanTransport(val label: String) { ETHERNET("Ethernet"), WIFI("Wi-Fi") }
enum class AdbConnectionKind(val label: String) { TLS("Wireless Debugging (TLS)"), ETHERNET_TCP("Ethernet ADB") }

data class LanLink(
    val id: String,
    val transport: LanTransport,
    val addresses: List<String>,
)

data class LocalNetworkState(val links: List<LanLink> = emptyList()) {
    val hasEthernet: Boolean get() = links.any { it.transport == LanTransport.ETHERNET && it.addresses.isNotEmpty() }
    val label: String get() = links.map { it.transport.label }.distinct().joinToString(" + ").ifBlank { "No local network" }
    fun addresses(transport: LanTransport? = null): List<String> = links
        .filter { transport == null || it.transport == transport }.flatMap { it.addresses }.distinct()
}

data class LocalAdbEndpoint(val name: String, val host: String, val port: Int, val kind: AdbConnectionKind)
enum class NetworkRecovery { KEEP, RECONNECT, STOP_AND_RECONNECT, UNAVAILABLE }

/** No host input or LAN scans: candidates must belong to the current device. */
object LocalConnectionPolicy {
    fun numericAddress(host: String): InetAddress? {
        val literal = host.substringBefore('%')
        if (host.contains('%') && !Regex("^[A-Za-z0-9_.-]+$").matches(host.substringAfter('%'))) return null
        if (literal.contains(':')) {
            if (!Regex("^[0-9A-Fa-f:.]+$").matches(literal)) return null
        } else {
            val octets = literal.split('.')
            if (octets.size != 4 || octets.any { part ->
                    !Regex("^[0-9]{1,3}$").matches(part) || part.toInt() !in 0..255
                }) return null
        }
        return runCatching { InetAddress.getByName(literal) }.getOrNull()
    }

    private fun sameAddress(first: String, second: String): Boolean {
        val a = numericAddress(first) ?: return false
        val b = numericAddress(second) ?: return false
        // Preserve link-local IPv6 scope: identical bytes on different interfaces are not equivalent.
        return a == b && (!a.isLinkLocalAddress || first.substringAfter('%', "") == second.substringAfter('%', ""))
    }

    fun isLocal(host: String, network: LocalNetworkState): Boolean =
        numericAddress(host)?.let { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress } == true &&
            network.addresses().any { sameAddress(host, it) }

    fun permits(endpoint: LocalAdbEndpoint, network: LocalNetworkState): Boolean = when (endpoint.kind) {
        AdbConnectionKind.TLS -> endpoint.port in 1..65535 && isLocal(endpoint.host, network)
        AdbConnectionKind.ETHERNET_TCP -> endpoint.port == 5555 &&
            isLocal(endpoint.host, network) &&
            network.addresses(LanTransport.ETHERNET).any { sameAddress(endpoint.host, it) }
    }

    fun candidates(network: LocalNetworkState, tls: List<LocalAdbEndpoint>): List<LocalAdbEndpoint> =
        (tls.filter { it.kind == AdbConnectionKind.TLS && permits(it, network) } +
            network.addresses(LanTransport.ETHERNET).map {
                LocalAdbEndpoint("this device's Ethernet ADB", it, 5555, AdbConnectionKind.ETHERNET_TCP)
            }).filter { permits(it, network) }.distinctBy { Triple(it.host, it.port, it.kind) }

    fun recovery(previous: LocalNetworkState, current: LocalNetworkState, busy: Boolean,
                 configured: Boolean): NetworkRecovery = when {
        previous == current -> NetworkRecovery.KEEP
        busy -> NetworkRecovery.STOP_AND_RECONNECT
        (configured || current.hasEthernet) && current.addresses().isNotEmpty() -> NetworkRecovery.RECONNECT
        else -> NetworkRecovery.UNAVAILABLE
    }

    fun connectionLabel(endpoint: LocalAdbEndpoint, network: LocalNetworkState): String =
        if (network.addresses(LanTransport.ETHERNET).any { sameAddress(endpoint.host, it) }) "Ethernet" else "Wi-Fi"
}
