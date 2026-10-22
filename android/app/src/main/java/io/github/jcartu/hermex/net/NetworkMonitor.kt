package io.github.jcartu.hermex.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Port of `Networking/NetworkPathMonitor.swift` on `ConnectivityManager`
 * callbacks: whether the device has a usable network, and a counter that
 * bumps when reachability flips or the interfaces change while satisfied
 * (Wi-Fi to cellular). Unknown counts as satisfied, so nothing waits before
 * the first update (#869).
 */
interface NetworkAvailability {
    /** False only once the network is known to be unavailable. */
    val isSatisfied: Boolean

    /** Bumps on every published path change; identical updates never reach it. */
    val changes: StateFlow<Int>
}

class NetworkMonitor(context: Context) : NetworkAvailability {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Networks currently validated for internet access, by handle. */
    private val availableNetworks = linkedMapOf<Long, Set<Int>>()
    private val lock = Any()

    private val _isSatisfied = MutableStateFlow(true)
    override val isSatisfied: Boolean get() = _isSatisfied.value

    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private var isStarted = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val transports = connectivityManager.getNetworkCapabilities(network)?.transports().orEmpty()
            publish { availableNetworks[network.networkHandle] = transports }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            publish { availableNetworks[network.networkHandle] = networkCapabilities.transports() }
        }

        override fun onLost(network: Network) {
            publish { availableNetworks.remove(network.networkHandle) }
        }

        override fun onUnavailable() {
            publish { availableNetworks.clear() }
        }
    }

    fun start() {
        synchronized(lock) {
            if (isStarted) return
            isStarted = true
        }
        publish {
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            if (network != null && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                availableNetworks[network.networkHandle] = capabilities.transports()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    /** Deduplicates like `NetworkPathDeduplicator`: only a changed snapshot publishes. */
    private fun publish(mutate: () -> Unit) {
        synchronized(lock) {
            val previousSnapshot = snapshot()
            mutate()
            val nextSnapshot = snapshot()
            if (previousSnapshot == nextSnapshot && _isSatisfied.value == nextSnapshot.first) return
            _isSatisfied.value = nextSnapshot.first
            _changes.update { it + 1 }
        }
    }

    private fun snapshot(): Pair<Boolean, Set<Int>> =
        availableNetworks.isNotEmpty() to availableNetworks.values.flatten().toSet()

    private fun NetworkCapabilities.transports(): Set<Int> =
        TRANSPORTS.filterTo(HashSet()) { hasTransport(it) }

    private companion object {
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
        )
    }
}
