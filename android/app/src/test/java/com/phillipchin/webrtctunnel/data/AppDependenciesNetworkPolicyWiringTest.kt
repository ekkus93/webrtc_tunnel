package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P0-003: proves production network policy event delivery diagnostics are reachable.
 * [com.phillipchin.webrtctunnel.network.NetworkPolicyManager] always owns a live
 * [AppDiagnosticEventBus] (no separate no-op/production reporter split left to
 * misconfigure), so this exercises the exact instance [AppDependencies] hands out.
 */
@RunWith(RobolectricTestRunner::class)
class AppDependenciesNetworkPolicyWiringTest {
    /**
     * Dispatchers.Unconfined runs a launch()ed collector synchronously up to its first
     * suspension point (i.e. until it's registered as an active subscriber) before the
     * launch() call returns — required here since the bus's replay is 0, so a subscriber
     * that started after an emission would miss it. Injected (default param, not an
     * inline reference) per this project's InjectDispatcher convention.
     */
    private fun unconfinedTestScope(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): CoroutineScope =
        CoroutineScope(Job() + dispatcher)

    @Test
    fun productionNetworkPolicyManagerDiagnosticEventsAreReachable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val deps = AppDependencies(context)

        val events = mutableListOf<DiagnosticEvent>()
        val scope = unconfinedTestScope()
        scope.launch {
            deps.networkPolicyManager.diagnosticEvents.events.collect { events.add(it) }
        }

        deps.networkPolicyManager.diagnosticEvents.reportDiagnosticEvent(
            DiagnosticEvent(code = "network_policy_event_delivery_failed", message = "simulated failure"),
        )

        assertEquals(1, events.size)
        assertEquals("network_policy_event_delivery_failed", events.single().code)
        scope.cancel()
    }
}
