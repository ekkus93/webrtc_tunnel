package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.RustTunnelBridge
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.network.LocalAddressResolver
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityRepository

class AppDependencies(
    context: Context,
    nativeBridgeFactory: () -> TunnelNativeBridge = { RustTunnelBridge() },
    val configRepository: ConfigRepository = ConfigRepository(context.applicationContext),
    val networkPolicyManager: NetworkPolicyManager = NetworkPolicyManager(context.applicationContext),
    val identityRepository: IdentityRepository = IdentityRepository(context.applicationContext),
    val dispatchers: AppDispatchers = AppDispatchers(),
) {
    val context: Context = context.applicationContext

    // App-wide snackbar bus: viewmodels emit one-shot success/failure messages here and the
    // top-level Scaffold renders them, so every mutating action gets a visible confirmation.
    val snackbar: SnackbarController = SnackbarController()

    // Resolves the active network's IPv4 (ConnectivityManager/LinkProperties) to advertise as
    // the vnet_mux host candidate; replaces the desktop-only 8.8.8.8 route probe on Android.
    val localAddressResolver: LocalAddressResolver = LocalAddressResolver(this.context)

    val diagnosticsRepository: DiagnosticsRepository =
        DiagnosticsRepository(this.context, configRepository = configRepository)

    val forwardsStore: ForwardsConfigStore = ForwardsConfigStore(this.context)

    // Single observable source of truth for configured forwards (Home + Forwards screens).
    val forwardsRepository: ForwardsRepository = ForwardsRepository(forwardsStore, dispatchers)

    // Transactional reset coordinator for atomic multi-file configuration resets (P2-003).
    val transactionalResetCoordinator: TransactionalResetCoordinator =
        TransactionalResetCoordinator(configRepository, forwardsRepository)

    // TunnelRepository (runtime/status) and IdentityValidationClient (config/identity
    // validation) are separate collaborators that must share a single native bridge,
    // created lazily on first use.
    private val sharedBridge: TunnelNativeBridge by lazy(nativeBridgeFactory)
    val tunnelRepository: TunnelRepository = TunnelRepository { sharedBridge }
    val identityValidation: IdentityValidationClient = IdentityValidationClient { sharedBridge }
}
