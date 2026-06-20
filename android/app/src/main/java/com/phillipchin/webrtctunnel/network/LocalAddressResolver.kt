package com.phillipchin.webrtctunnel.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * Resolves the local IPv4 address to advertise as the WebRTC host candidate on Android,
 * sourced from `ConnectivityManager`/`LinkProperties` for the active network. This replaces
 * the hard-coded `8.8.8.8` UDP-route probe, which is not used in Android production: the
 * native `vnet_mux` path advertises whatever address this resolves and binds a `0.0.0.0` mux
 * socket for I/O.
 *
 * The framework lookup is injected as [candidateProvider] so the selection logic can be
 * unit-tested without a live network.
 */
class LocalAddressResolver internal constructor(
    private val candidateProvider: () -> List<String>,
) {
    constructor(context: Context) : this({ activeLinkIpv4Addresses(context.applicationContext) })

    /** The routable IPv4 to advertise, or `null` if the active network exposes none. */
    fun currentIpv4(): String? = selectRoutableIpv4(candidateProvider())

    companion object {
        private const val IPV4_OCTET_COUNT = 4
        private const val OCTET_MAX = 255
        private const val LOOPBACK_FIRST_OCTET = 127
        private const val MULTICAST_FIRST_MIN = 224
        private const val MULTICAST_FIRST_MAX = 239
        private const val BROADCAST_FIRST_OCTET = 255
        private const val LINK_LOCAL_FIRST_OCTET = 169
        private const val LINK_LOCAL_SECOND_OCTET = 254

        /**
         * Pick the best routable IPv4 from [candidates]: skip loopback/unspecified/multicast/
         * broadcast, and prefer a normal (non link-local) address, only falling back to a
         * `169.254/16` link-local address when nothing better is present.
         */
        fun selectRoutableIpv4(candidates: List<String>): String? {
            val routable = candidates.map { it.trim() }.filter { isRoutableIpv4(it) }
            return routable.firstOrNull { !isLinkLocalIpv4(it) } ?: routable.firstOrNull()
        }

        private fun isRoutableIpv4(value: String): Boolean {
            val octets = parseIpv4Octets(value) ?: return false
            val first = octets[0]
            return when {
                first == 0 -> false // unspecified / "this network"
                first == LOOPBACK_FIRST_OCTET -> false
                first == BROADCAST_FIRST_OCTET -> false
                first in MULTICAST_FIRST_MIN..MULTICAST_FIRST_MAX -> false
                else -> true
            }
        }

        private fun isLinkLocalIpv4(value: String): Boolean {
            val octets = parseIpv4Octets(value) ?: return false
            return octets[0] == LINK_LOCAL_FIRST_OCTET && octets[1] == LINK_LOCAL_SECOND_OCTET
        }

        private fun parseIpv4Octets(value: String): List<Int>? {
            val parts = value.split('.')
            val octets = parts.mapNotNull { it.toIntOrNull() }
            return octets.takeIf {
                parts.size == IPV4_OCTET_COUNT &&
                    it.size == IPV4_OCTET_COUNT &&
                    it.all { octet -> octet in 0..OCTET_MAX }
            }
        }

        private fun activeLinkIpv4Addresses(context: Context): List<String> {
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val link = network?.let { cm.getLinkProperties(it) }
            return link?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    }
}
