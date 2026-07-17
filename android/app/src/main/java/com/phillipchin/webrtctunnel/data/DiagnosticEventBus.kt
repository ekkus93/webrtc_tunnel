package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An app-level diagnostic event — a redacted code/message pair safe to log or display.
 */
data class DiagnosticEvent(
    val code: String,
    val message: String,
)

/**
 * Reports diagnostic events from app-wide singletons (e.g. [com.phillipchin.webrtctunnel.network.NetworkPolicyManager])
 * that are not owned by any single service/component and therefore have no direct
 * access to a service-scoped error reporter.
 */
interface DiagnosticEventReporter {
    fun reportDiagnosticEvent(event: DiagnosticEvent)
}

/**
 * App-wide diagnostic event bus (P0-003). Singletons constructed in [AppDependencies]
 * report through this bus; [com.phillipchin.webrtctunnel.TunnelForegroundService] collects
 * from it while running and relays events to its own visible error/notification reporter.
 */
class AppDiagnosticEventBus : DiagnosticEventReporter {
    private val _events = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DiagnosticEvent> = _events.asSharedFlow()

    override fun reportDiagnosticEvent(event: DiagnosticEvent) {
        _events.tryEmit(event)
    }
}
