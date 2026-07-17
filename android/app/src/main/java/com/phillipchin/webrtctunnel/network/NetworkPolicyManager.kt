package com.phillipchin.webrtctunnel.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Direct, required reporter for network-policy diagnostics (FIX6 P0-002 / INV-004).
 *
 * A `fun interface` taking only a redacted [String], never a `Throwable`. It replaces the
 * previous `AppDiagnosticEventBus`, whose replay-zero `SharedFlow` + ignored `tryEmit`
 * dropped a required diagnostic when no subscriber was registered — the exact silent
 * discard P0-003 was meant to eliminate. There is no default/no-op implementation:
 * `monitor` requires a reporter, so a caller cannot forget to supply one.
 */
fun interface NetworkPolicyDiagnosticReporter {
    fun report(
        code: String,
        message: String,
    )
}

class NetworkPolicyManager(
    private val classifier: () -> Pair<NetworkType, Boolean>,
) {
    constructor(context: Context) : this({ classifyCurrentNetwork(context) })

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

    fun monitor(
        context: Context,
        reporter: NetworkPolicyDiagnosticReporter,
    ): Flow<NetworkPolicyStatus> =
        callbackFlow {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = evaluateAndEmit(reporter)

                    override fun onLost(network: android.net.Network) = evaluateAndEmit(reporter)

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: NetworkCapabilities,
                    ) = evaluateAndEmit(reporter)
                }
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, callback)
            evaluateAndEmit(reporter)
            awaitClose {
                // P0-006-C: awaitClose cannot suspend and must not throw raw callback exceptions
                // out of cleanup; report a redacted diagnostic directly instead.
                reportUnregisterFailure(reporter) { cm.unregisterNetworkCallback(callback) }
            }
        }.conflate()

    /**
     * P0-006-A: classify the current network and emit its policy status, failing closed if
     * classification throws. A classification failure reports a redacted diagnostic and emits a
     * fail-closed [NetworkType.Unknown] status (whose `tunnelAllowed` is false) rather than
     * letting an arbitrary exception escape an Android callback. Shared by every callback and the
     * initial emission so all paths fail closed identically.
     */
    private fun ProducerScope<NetworkPolicyStatus>.evaluateAndEmit(reporter: NetworkPolicyDiagnosticReporter) {
        val current =
            try {
                evaluate(classifier(), allowMetered = false)
            } catch (error: Exception) {
                reporter.report(
                    code = "network_policy_classification_failed",
                    message =
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Network policy classification failed",
                        ),
                )
                evaluate(NetworkType.Unknown to false, allowMetered = false)
            }
        _status.value = current
        emitPolicyStatus(current, reporter)
    }

    companion object {
        private const val TAG = "NetworkPolicyManager"

        private fun ProducerScope<NetworkPolicyStatus>.emitPolicyStatus(
            status: NetworkPolicyStatus,
            reporter: NetworkPolicyDiagnosticReporter,
        ) = handlePolicyDeliveryResult(trySend(status), reporter)

        /**
         * Handles the real [trySend] result (P0-002-E). Extracted so tests can drive the
         * actual delivery-result path — a genuinely failed `trySend`, an expected close —
         * rather than only exercising the [isExpectedChannelClose] classifier.
         *
         * The reporter receives a redacted [String]; the raw `Throwable` is never passed to
         * it or to `Log.w(tag, msg, throwable)`, whose stack-trace header would print the
         * unredacted message.
         */
        internal fun handlePolicyDeliveryResult(
            result: ChannelResult<Unit>,
            reporter: NetworkPolicyDiagnosticReporter,
        ) {
            if (result.isSuccess) return
            val cause = result.exceptionOrNull()
            if (isExpectedChannelClose(cause)) return
            val message = redactedDeliveryFailureMessage(cause)
            Log.w(TAG, "Network policy event delivery failed: $message")
            reporter.report(
                code = "network_policy_event_delivery_failed",
                message = message,
            )
        }

        /**
         * P0-006-C: run [unregister] and, if it throws, report a redacted diagnostic instead of
         * letting a raw callback exception escape `awaitClose` (which cannot suspend). Extracted
         * so tests can drive a genuinely failing unregister without mocking ConnectivityManager.
         */
        internal fun reportUnregisterFailure(
            reporter: NetworkPolicyDiagnosticReporter,
            unregister: () -> Unit,
        ) {
            try {
                unregister()
            } catch (error: Exception) {
                reporter.report(
                    code = "network_policy_unregister_failed",
                    message =
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Failed to unregister network callback",
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
