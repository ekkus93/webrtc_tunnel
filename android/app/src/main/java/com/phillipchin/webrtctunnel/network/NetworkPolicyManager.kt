package com.phillipchin.webrtctunnel.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.phillipchin.webrtctunnel.data.AppDiagnosticEventBus
import com.phillipchin.webrtctunnel.data.DiagnosticEvent
import com.phillipchin.webrtctunnel.data.DiagnosticEventReporter
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class NetworkPolicyManager(
    private val classifier: () -> Pair<NetworkType, Boolean>,
) {
    constructor(context: Context) : this({ classifyCurrentNetwork(context) })

    // P0-003: always-owned diagnostic bus — delivery failures are never silently
    // discarded, since there is no separate no-op/production reporter split to
    // misconfigure. TunnelForegroundService collects from this while alive.
    val diagnosticEvents: AppDiagnosticEventBus = AppDiagnosticEventBus()

    private val _status = MutableStateFlow(evaluate(classifier(), allowMetered = false))
    val status: StateFlow<NetworkPolicyStatus> = _status.asStateFlow()

    fun refresh() {
        _status.value = evaluate(classifier(), allowMetered = false)
    }

    fun evaluateWithPolicy(allowMetered: Boolean): NetworkPolicyStatus {
        val evaluated = evaluate(classifier(), allowMetered)
        _status.value = evaluated
        return evaluated
    }

    fun allowTunnelOnCurrentNetwork(allowMetered: Boolean): Boolean {
        return evaluateWithPolicy(allowMetered).tunnelAllowed
    }

    fun monitor(context: Context): Flow<NetworkPolicyStatus> =
        callbackFlow {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        emitPolicyStatus(current, diagnosticEvents)
                    }

                    override fun onLost(network: android.net.Network) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        emitPolicyStatus(current, diagnosticEvents)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val current = evaluate(classifier(), allowMetered = false)
                        _status.value = current
                        emitPolicyStatus(current, diagnosticEvents)
                    }
                }
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, callback)
            emitPolicyStatus(evaluate(classifier(), allowMetered = false), diagnosticEvents)
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }.conflate()

    companion object {
        private const val TAG = "NetworkPolicyManager"

        /**
         * Wraps trySend so delivery failures are visible (logged) and reported
         * through [diagnostics] for app-level diagnostics.
         */
        private fun ProducerScope<NetworkPolicyStatus>.emitPolicyStatus(
            status: NetworkPolicyStatus,
            diagnostics: DiagnosticEventReporter,
        ) {
            val result = trySend(status)
            if (result.isFailure) {
                val cause = result.exceptionOrNull()
                if (isExpectedChannelClose(cause)) {
                    return
                }
                val message = redactedDeliveryFailureMessage(cause)
                Log.w(TAG, "Network policy event delivery failed: $message")
                diagnostics.reportDiagnosticEvent(
                    DiagnosticEvent(
                        code = "network_policy_event_delivery_failed",
                        message = message,
                    ),
                )
            }
        }

        /**
         * Returns true if the cause is a cancellation or closed channel —
         * delivery failures in these cases are expected and should not be reported.
         */
        internal fun isExpectedChannelClose(cause: Throwable?): Boolean =
            cause is kotlinx.coroutines.CancellationException ||
                cause is kotlinx.coroutines.channels.ClosedSendChannelException

        /**
         * Converts a delivery-failure cause into a redacted string. Never returns or logs
         * the original Throwable — its message may carry secrets and Log.w(tag, msg,
         * throwable) would print it unredacted via the throwable's own toString().
         */
        internal fun redactedDeliveryFailureMessage(cause: Throwable?): String {
            val raw = cause?.message ?: "Network policy event could not be delivered"
            return SensitiveDataRedactor.redactText(raw)
        }

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
        ): NetworkPolicyStatus {
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
            return NetworkPolicyStatus(
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
