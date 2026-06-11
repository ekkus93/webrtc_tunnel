package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityRepository

class AppDependencies(
    context: Context,
    nativeBridgeFactory: () -> TunnelNativeBridge = { RustTunnelBridge() },
    val configRepository: ConfigRepository = ConfigRepository(context.applicationContext),
    val networkPolicyManager: NetworkPolicyManager = NetworkPolicyManager(context.applicationContext),
    val identityRepository: IdentityRepository = IdentityRepository(context.applicationContext),
    val diagnosticsRepository: DiagnosticsRepository =
        DiagnosticsRepository(
            context.applicationContext,
            configRepository = configRepository,
        ),
) {
    val context: Context = context.applicationContext

    val forwardsStore: ForwardsConfigStore = ForwardsConfigStore(this.context)

    // TunnelRepository (runtime/status) and IdentityValidationClient (config/identity
    // validation) are separate collaborators that must share a single native bridge,
    // created lazily on first use.
    private val sharedBridge: TunnelNativeBridge by lazy(nativeBridgeFactory)
    val tunnelRepository: TunnelRepository = TunnelRepository { sharedBridge }
    val identityValidation: IdentityValidationClient = IdentityValidationClient { sharedBridge }
}
