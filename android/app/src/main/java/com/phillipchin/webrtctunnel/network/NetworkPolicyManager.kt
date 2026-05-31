package com.phillipchin.webrtctunnel.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.channels.awaitClose

class NetworkPolicyManager internal constructor(
    private val classifier: () -> NetworkStatus,
) {
    constructor(context: Context) : this({ classifyCurrentNetwork(context) })

    private val _status = MutableStateFlow(classifier())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun refresh() {
        _status.value = classifier()
    }

    fun allowTunnelOnCurrentNetwork(allowMetered: Boolean): Boolean {
        val status = classifier()
        return when (status.networkType) {
            NetworkType.NoNetwork, NetworkType.Unknown -> false
            NetworkType.Cellular, NetworkType.MeteredWifi -> allowMetered
            NetworkType.UnmeteredWifi -> true
        }
    }

    fun monitor(context: Context): Flow<NetworkStatus> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val current = classifier()
                _status.value = current
                trySend(current)
            }

            override fun onLost(network: android.net.Network) {
                val current = classifier()
                _status.value = current
                trySend(current)
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val current = classifier()
                _status.value = current
                trySend(current)
            }
        }
        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, callback)
        trySend(classifier())
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate()

    private companion object {
        fun classifyCurrentNetwork(context: Context): NetworkStatus {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return NetworkStatus(NetworkType.NoNetwork, false, false, "No network")
            val capabilities = cm.getNetworkCapabilities(network)
                ?: return NetworkStatus(NetworkType.Unknown, false, false, "Unknown network")
            val metered = cm.isActiveNetworkMetered
            val networkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !metered -> NetworkType.UnmeteredWifi
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.MeteredWifi
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
                else -> NetworkType.Unknown
            }
            val allowed = networkType == NetworkType.UnmeteredWifi
            val reason = if (allowed) null else "Tunnel blocked by policy"
            return NetworkStatus(networkType, metered, allowed, reason)
        }
    }
}
