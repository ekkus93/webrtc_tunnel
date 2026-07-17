package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Explicit application-initialization readiness (FIX6 INV-010).
 *
 * Replaces the previous silent model, where `Application.onCreate()` ran default-config
 * creation inside `runBlocking` on the main thread and discarded its result: a failed
 * creation left the app running with no config and no indication anything was wrong.
 */
sealed interface AppInitializationState {
    data object Initializing : AppInitializationState

    data object Ready : AppInitializationState

    data class Failed(
        val code: String,
        val message: String,
    ) : AppInitializationState
}

/**
 * Owns default-config creation off the main thread and publishes the outcome as
 * observable readiness. Start requests are gated on [AppInitializationState.Ready], so a
 * failure here is visible and blocks native start rather than being discovered later as a
 * confusing config-missing failure.
 */
class AppInitializationCoordinator(
    private val configRepository: ConfigRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<AppInitializationState>(AppInitializationState.Initializing)
    val state: StateFlow<AppInitializationState> = _state.asStateFlow()

    /** Launches initialization. Returns the job so teardown/tests can join it. */
    fun start(): Job = scope.launch(ioDispatcher) { initialize() }

    /**
     * Runs initialization inline. Exposed so tests (and the test application) can reach a
     * deterministic terminal state without racing a launched coroutine.
     */
    suspend fun initialize() {
        _state.value =
            configRepository
                .ensureDefaultConfig(configRepository.defaultConfigTemplate)
                .fold(
                    onSuccess = { AppInitializationState.Ready },
                    onFailure = { error ->
                        AppInitializationState.Failed(
                            code = "config_initialization_failed",
                            message =
                                SensitiveDataRedactor.redactText(
                                    error.message ?: "Failed to initialize configuration",
                                ),
                        )
                    },
                )
    }
}
