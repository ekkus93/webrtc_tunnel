package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityRepository

class AppDependencies(
    context: Context,
    val configRepository: ConfigRepository = ConfigRepository(context.applicationContext),
    val tunnelRepository: TunnelRepository = TunnelRepository(context.applicationContext),
    val networkPolicyManager: NetworkPolicyManager = NetworkPolicyManager(context.applicationContext),
    val identityRepository: IdentityRepository = IdentityRepository(context.applicationContext),
) {
    val context: Context = context.applicationContext
}
