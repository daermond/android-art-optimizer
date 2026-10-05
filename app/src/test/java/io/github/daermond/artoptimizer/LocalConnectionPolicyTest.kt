package io.github.daermond.artoptimizer

import org.junit.Assert.*
import org.junit.Test

class LocalConnectionPolicyTest {
    private fun network(type: LanTransport, host: String = "192.168.0.107", id: String = "1") =
        LocalNetworkState(listOf(LanLink(id, type, listOf(host))))
    private val wifi = network(LanTransport.WIFI)
    private val ethernet = network(LanTransport.ETHERNET)
    private fun tls(host: String = "192.168.0.107", port: Int = 37001) =
        LocalAdbEndpoint("local TLS", host, port, AdbConnectionKind.TLS)

    @Test fun wifiRetainsTlsOnlyWithItsDiscoveredDynamicPort() {
        assertEquals(listOf(tls()), LocalConnectionPolicy.candidates(wifi, listOf(tls())))
        assertTrue(LocalConnectionPolicy.candidates(wifi, emptyList()).isEmpty())
    }

    @Test fun ethernetCanConnectWithoutPairingOrMdnsAndTlsIsPreferredWhenAdvertised() {
        val tcp = LocalConnectionPolicy.candidates(ethernet, emptyList()).single()
        assertEquals(5555, tcp.port)
        assertEquals(AdbConnectionKind.ETHERNET_TCP, tcp.kind)
        assertEquals("192.168.0.107", tcp.host)
        assertEquals(listOf(tls(), tcp), LocalConnectionPolicy.candidates(ethernet, listOf(tls())))
    }

    @Test fun remoteHostsHostnamesAndOtherPortsNeverBecomeWiredCandidates() {
        val tcp = LocalAdbEndpoint("wired", "192.168.0.108", 5555, AdbConnectionKind.ETHERNET_TCP)
        assertFalse(LocalConnectionPolicy.permits(tcp, ethernet))
        assertFalse(LocalConnectionPolicy.permits(tcp.copy(host = "192.168.0.107", port = 5556), ethernet))
        assertFalse(LocalConnectionPolicy.permits(tcp.copy(host = "192.168.0.107"), wifi))
        assertFalse(LocalConnectionPolicy.permits(tcp.copy(host = "localhost"), ethernet))
        assertNull(LocalConnectionPolicy.numericAddress("example.com"))
        assertNull(LocalConnectionPolicy.numericAddress("face.be"))
        assertNull(LocalConnectionPolicy.numericAddress("999.1.1.1"))
        assertNull(LocalConnectionPolicy.numericAddress("1234"))
        assertNull(LocalConnectionPolicy.numericAddress("127.0.0.1;id"))
        assertTrue(LocalConnectionPolicy.candidates(ethernet, listOf(tls("192.168.0.108"))).all {
            it.kind == AdbConnectionKind.ETHERNET_TCP
        })
    }

    @Test fun addressChangesDropStaleTlsAndTcpAndDoNotSaveTheOldHost() {
        val changed = network(LanTransport.ETHERNET, "192.168.0.109")
        assertEquals(listOf("192.168.0.109"), LocalConnectionPolicy.candidates(changed, listOf(tls())).map { it.host })
        assertFalse(LocalConnectionPolicy.permits(LocalConnectionPolicy.candidates(ethernet, emptyList()).single(), changed))
    }

    @Test fun multipleLinksOnlyGenerateTcpOnEthernetAndReportActualConnectedMedium() {
        val both = LocalNetworkState(wifi.links + network(LanTransport.ETHERNET, "192.168.0.109", "2").links)
        val endpoints = LocalConnectionPolicy.candidates(both, listOf(tls()))
        assertEquals("Wi-Fi", LocalConnectionPolicy.connectionLabel(endpoints.first(), both))
        assertEquals("Ethernet", LocalConnectionPolicy.connectionLabel(endpoints.last(), both))
        assertTrue(both.label.contains("Wi-Fi") && both.label.contains("Ethernet"))
    }

    @Test fun ipv6LiteralsAreComparedByAddressAndLinkLocalScopeIsPreserved() {
        val v6 = network(LanTransport.WIFI, "2001:db8::1")
        assertTrue(LocalConnectionPolicy.permits(tls("2001:0db8:0:0:0:0:0:1"), v6))
        val scoped = network(LanTransport.ETHERNET, "fe80::1%eth0")
        assertTrue(LocalConnectionPolicy.permits(tls("fe80::1%eth0"), scoped))
        assertFalse(LocalConnectionPolicy.permits(tls("fe80::1%wlan0"), scoped))
        assertFalse(LocalConnectionPolicy.permits(tls("127.0.0.1"), network(LanTransport.WIFI, "127.0.0.1")))
    }

    @Test fun idleNetworkSwitchesReconnectButUnchangedSnapshotsDoNothing() {
        assertEquals(NetworkRecovery.RECONNECT, LocalConnectionPolicy.recovery(wifi, ethernet, false, true))
        assertEquals(NetworkRecovery.RECONNECT, LocalConnectionPolicy.recovery(ethernet, wifi, false, true))
        assertEquals(NetworkRecovery.KEEP, LocalConnectionPolicy.recovery(wifi, wifi, true, true))
        assertEquals(NetworkRecovery.UNAVAILABLE, LocalConnectionPolicy.recovery(wifi, LocalNetworkState(), false, true))
    }

    @Test fun interruptedWorkStopsBeforeAutomaticReconnectAndFreshEthernetIsDetected() {
        assertEquals(NetworkRecovery.STOP_AND_RECONNECT, LocalConnectionPolicy.recovery(wifi, ethernet, true, true))
        assertEquals(NetworkRecovery.RECONNECT, LocalConnectionPolicy.recovery(LocalNetworkState(), ethernet, false, false))
        assertEquals(NetworkRecovery.UNAVAILABLE, LocalConnectionPolicy.recovery(LocalNetworkState(), wifi, false, false))
    }
}
