package io.github.daermond.artoptimizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface LocalNetworkMonitor : AutoCloseable { val state: StateFlow<LocalNetworkState> }

/** Tracks physical LAN links, including networks without internet validation. */
class AndroidLocalNetworkMonitor(context: Context) : LocalNetworkMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
    private val properties = mutableMapOf<Network, LinkProperties>()
    private val mutable = MutableStateFlow(LocalNetworkState())
    override val state: StateFlow<LocalNetworkState> = mutable
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, value: NetworkCapabilities) {
            capabilities[network] = value
            publish()
        }
        override fun onLinkPropertiesChanged(network: Network, value: LinkProperties) {
            properties[network] = value
            publish()
        }
        override fun onLost(network: Network) {
            capabilities.remove(network)
            properties.remove(network)
            publish()
        }
    }

    init {
        // All mutations run on the main thread, including the initial snapshot.
        manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback,
            Handler(Looper.getMainLooper()))
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities[network] = it }
            manager.getLinkProperties(network)?.let { properties[network] = it }
        }
        publish()
    }

    private fun publish() {
        if (closed) return
        mutable.value = LocalNetworkState(capabilities.flatMap { (network, caps) ->
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
            val addresses = properties[network]?.linkAddresses.orEmpty().map { it.address }
                .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
                .mapNotNull { it.hostAddress }.distinct().sorted()
            listOf(LanTransport.ETHERNET to NetworkCapabilities.TRANSPORT_ETHERNET,
                LanTransport.WIFI to NetworkCapabilities.TRANSPORT_WIFI).mapNotNull { (type, code) ->
                if (caps.hasTransport(code) && addresses.isNotEmpty()) LanLink(network.toString(), type, addresses)
                else null
            }
        }.sortedWith(compareBy({ it.transport.ordinal }, { it.id })))
    }

    override fun close() {
        closed = true
        manager.unregisterNetworkCallback(callback)
    }
}
