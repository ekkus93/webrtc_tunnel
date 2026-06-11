package com.phillipchin.webrtctunnel.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class NetworkPolicyManager internal constructor(
    private val classifier: () -> Pair<NetworkType, Boolean>,
) {
    constructor(context: Context) : this({ classifyCurrentNetwork(context) })

    private val _status = MutableStateFlow(evaluate(classifier(), allowMetered = false))
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun refresh() {
        _status.value = evaluate(classifier(), allowMetered = false)
    }

    fun evaluateWithPolicy(allowMetered: Boolean): NetworkStatus {
        val evaluated = evaluate(classifier(), allowMetered)
        _status.value = evaluated
        return evaluated
    }

    fun allowTunnelOnCurrentNetwork(allowMetered: Boolean): Boolean {
        return evaluateWithPolicy(allowMetered).tunnelAllowed
    }

    fun monitor(context: Context): Flow<NetworkStatus> =
        callbackFlow {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        trySend(current)
                    }

                    override fun onLost(network: android.net.Network) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        trySend(current)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        trySend(current)
                    }
                }
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, callback)
            trySend(evaluate(classifier(), allowMetered = false))
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }.conflate()

    private companion object {
        fun classifyCurrentNetwork(context: Context): Pair<NetworkType, Boolean> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = network?.let { cm.getNetworkCapabilities(it) }
            val metered = cm.isActiveNetworkMetered
            return when {
                network == null -> NetworkType.NoNetwork to false
                capabilities == null -> NetworkType.Unknown to false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !metered ->
                    NetworkType.UnmeteredWifi to metered
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.MeteredWifi to metered
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular to metered
                else -> NetworkType.Unknown to metered
            }
        }

        fun evaluate(
            snapshot: Pair<NetworkType, Boolean>,
            allowMetered: Boolean,
        ): NetworkStatus {
            val (networkType, isMetered) = snapshot
            val allowedByDefault = networkType == NetworkType.UnmeteredWifi
            val allowedByUserPolicy =
                when (networkType) {
                    NetworkType.UnmeteredWifi -> true
                    NetworkType.MeteredWifi, NetworkType.Cellular -> allowMetered
                    NetworkType.NoNetwork, NetworkType.Unknown -> false
                }
            val reason =
                when {
                    networkType == NetworkType.NoNetwork -> "No network"
                    networkType == NetworkType.Unknown -> "Unknown network"
                    allowedByUserPolicy -> null
                    else -> "Tunnel blocked by policy"
                }
            return NetworkStatus(
                networkType = networkType,
                isMetered = isMetered,
                allowedByDefault = allowedByDefault,
                allowedByUserPolicy = allowedByUserPolicy,
                tunnelAllowed = allowedByUserPolicy,
                blockReason = reason,
            )
        }
    }
}
