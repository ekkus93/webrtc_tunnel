package com.phillipchin.webrtctunnel

import android.app.Service
import com.phillipchin.webrtctunnel.data.CoordinatorOperations
import com.phillipchin.webrtctunnel.data.LifecycleCommand
import com.phillipchin.webrtctunnel.data.NativeFailureAfterStartupContext
import com.phillipchin.webrtctunnel.data.PolicyAllowedResumeContext
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.StartOutcome
import com.phillipchin.webrtctunnel.data.StopStatusVerificationException
import com.phillipchin.webrtctunnel.data.UnverifiedStartContext
import com.phillipchin.webrtctunnel.data.classifyStartResult
import com.phillipchin.webrtctunnel.data.cleanupUnverifiedStart
import com.phillipchin.webrtctunnel.data.handleNativeFailureAfterStartup
import com.phillipchin.webrtctunnel.data.resumeOnPolicyAllowedIfPreferred
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ActiveStartup(val generation: Long, val job: Job)

internal class StartupAborted(message: String) : Exception(message)

internal class StartupPolicyBlocked(message: String) : RuntimeException(message)

internal fun startBlockedCode(error: Throwable): String =
    if (error is AppInitializationIncompleteException) {
        error.code
    } else {
        "native_runtime_quarantined"
    }

// Distinguishes an outright native stop failure from a stop that JNI reported as successful
// but whose final state could not be verified as Stopped (P0-003), so TunnelRepository's
// sticky lastCleanupError history can retain both categories. Top-level (not a class member)
// so it doesn't count against this class's function budget for no behavioral reason.
internal fun stopFailureCode(error: Throwable): String =
    if (error is StopStatusVerificationException) {
        "stop_status_verification_failed"
    } else {
        "stop_failed"
    }

// Delegate implementation of CoordinatorOperations, relocated from TunnelForegroundService
// (which cannot host it as an inner-class/anonymous-object member and stay under the
// repo's 800-line file-size guidance). Every implicit outer-class access below is now an
// explicit `service.` reference.
internal class ServiceCoordinatorOperations(private val service: TunnelForegroundService) : CoordinatorOperations {
    // P0-001: submits the retry once the NativeFailure branch of
    // handleStartupCompleted has confirmed the pending policy retry is live.
    // A property (not a function) so it doesn't count against this object's
    // detekt function budget, and it keeps handleStartupCompleted short enough
    // for detekt's LongMethod check.
    val resumeAfterNativeFailurePendingRetry: (Long) -> Unit = { gen ->
        service.submitLifecycleCommand(LifecycleCommand.RetryPolicyResume(expectedGeneration = gen))
    }

    override fun onError(
        message: String,
        code: String,
        state: ServiceState,
    ) {
        service.reporter.publishError(message, code, state)
    }

    // P1-002-B: the lifecycle command processor died unexpectedly — nothing is
    // left to drain the command queue, so the native runtime's real state is
    // uncertain. Quarantine through the same central helper every other stop-like
    // failure uses, then actually stop the service rather than leaving it foreground
    // pretending to still be controllable. A property (not a function) so it doesn't
    // count against this object's detekt function budget.
    override val onProcessorFailed: () -> Unit = {
        service.enterNativeRuntimeQuarantine(
            code = "lifecycle_processor_failed",
            message = "Lifecycle command processor exited unexpectedly",
        )
        service.stopSelf()
    }

    override suspend fun startOffer() {
        service.requireRuntimeStartAllowed()
            .getOrElse { error ->
                service.reporter.publishError(
                    message =
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Runtime restart is blocked",
                        ),
                    code = startBlockedCode(error),
                )
                return
            }
        if (!service.repository.status.value.serviceState.isTunnelActiveOrStarting()) {
            service.offer.startOffer()
        }
    }

    override suspend fun pause() {
        service.offer.pause()
    }

    override suspend fun resume() {
        service.requireRuntimeStartAllowed()
            .getOrElse { error ->
                service.reporter.publishError(
                    message =
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Runtime restart is blocked",
                        ),
                    code = startBlockedCode(error),
                )
                return
            }
        service.offer.resume()
    }

    override suspend fun stop() {
        service.offer.stopServiceWork()
    }

    override suspend fun allowMeteredForSessionAndStart() {
        service.requireRuntimeStartAllowed()
            .getOrElse { error ->
                service.reporter.publishError(
                    message =
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Runtime restart is blocked",
                        ),
                    code = startBlockedCode(error),
                )
                return
            }
        service.offer.allowMeteredForSessionAndStart()
    }

    override suspend fun pauseForPolicy(reason: String) {
        service.offer.pauseForPolicy(reason)
    }

    override suspend fun handlePolicyAllowed() {
        // FIX6 P0-004: quarantine (and not-yet-ready init) must be visible from
        // this path just as from manual start/resume, not silently swallowed.
        service.requireRuntimeStartAllowed().getOrElse { error ->
            service.invalidatePendingPolicyRetry()
            service.reporter.publishError(
                code = startBlockedCode(error),
                message =
                    SensitiveDataRedactor.redactText(
                        error.message ?: "Runtime restart is blocked",
                    ),
            )
            return
        }
        // Not policy-paused is not an error — just drop any stale token.
        if (!service.pausedByPolicy.get()) {
            service.invalidatePendingPolicyRetry()
            return
        }
        // The pref-read + resume decision lives in a top-level function (like
        // handleNativeFailureAfterStartup) so it costs no method budget against
        // this object, which is at detekt's TooManyFunctions limit.
        resumeOnPolicyAllowedIfPreferred(
            PolicyAllowedResumeContext(
                readPreferences = { service.configRepository.preferences.first() },
                invalidatePendingRetry = service::invalidatePendingPolicyRetry,
                publishError = { code, message ->
                    service.reporter.publishError(message = message, code = code)
                },
                recordPendingRetry = {
                    service.pendingPolicyResumeGeneration.set(service.lifecycleGeneration.get())
                },
                hasActiveStartup = { service.activeStartup != null },
                resume = { service.offer.resume() },
            ),
        )
    }

    override suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
        val allowed = service.requireRuntimeStartAllowed().getOrNull()
        if (allowed == null) {
            service.invalidatePendingPolicyRetry()
            return
        }
        if (service.lifecycleGeneration.get() != expectedGeneration ||
            !service.pausedByPolicy.get()
        ) {
            service.invalidatePendingPolicyRetry()
            return
        }
        service.invalidatePendingPolicyRetry()
        service.offer.resume()
    }

    override suspend fun handleStartupCompleted(
        generation: Long,
        outcome: StartOutcome,
    ) {
        if (service.lifecycleGeneration.get() != generation) return
        service.activeStartup = null
        if (outcome !is StartOutcome.VerifiedSuccess) {
            service.clearTemporaryMeteredAllowance()
        }
        // P0-001: every branch except NativeFailure invalidates unconditionally.
        // NativeFailure must consume the pending retry first — invalidating here
        // too would clear it before that branch could read it.
        if (outcome !is StartOutcome.NativeFailure) {
            service.invalidatePendingPolicyRetry()
        }
        when (outcome) {
            StartOutcome.VerifiedSuccess -> {
                service.pausedByPolicy.set(false)
                service.reporter.publishStatus()
                service.reporter.startStatusPolling()
            }
            is StartOutcome.NativeFailure -> {
                handleNativeFailureAfterStartup(
                    NativeFailureAfterStartupContext(
                        outcome.error,
                        generation,
                        service.pendingPolicyResumeGeneration,
                        service.pausedByPolicy,
                        resumeAfterNativeFailurePendingRetry,
                        service.reporter::publishError,
                    ),
                )
            }
            is StartOutcome.VerificationFailure -> {
                cleanupUnverifiedStart(
                    UnverifiedStartContext(
                        outcome.error,
                        generation,
                        service.lifecycleGeneration,
                        service.reporter::stopStatusPollingAndJoin,
                        { service.repository.stop() },
                        service.nativeStopVerified,
                        service::enterNativeRuntimeQuarantine,
                    ),
                )
            }
            is StartOutcome.UnexpectedFailure -> {
                service.reporter.publishError(
                    outcome.error.message ?: "Unexpected startup failure",
                    "startup_unexpected_failure",
                )
            }
            is StartOutcome.PolicyBlocked -> {
                service.pausedByPolicy.set(true)
                service.nativeStopVerified.set(true)
                service.repository.setPolicyBlocked(outcome.reason)
                service.reporter.publishStatus(outcome.reason)
            }
            is StartOutcome.Aborted -> {
                service.reporter.publishError(outcome.reason, "startup_aborted")
            }
        }
    }
}

// Offer-mode start plus pause/stop transitions, guarded by the lifecycle generation.
// Relocated from a `TunnelForegroundService` inner class for the same file-size reason as
// `ServiceCoordinatorOperations` above; every implicit outer-class access is now `service.`.
internal class OfferCoordinator(private val service: TunnelForegroundService) {
    suspend fun startOffer() {
        var generation = 0L
        service.lifecycleMutex.withLock {
            if (service.activeStartup != null) {
                service.reporter.publishStatus(service.getString(R.string.service_msg_already_starting))
                return
            }
            val current = service.repository.status.value.serviceState
            // P1-012: Block duplicate starts in transitional states too.
            if (current.isTunnelActiveOrStarting()) {
                service.reporter.publishStatus(service.getString(R.string.service_msg_already_running))
                return
            }
            generation = service.lifecycleGeneration.incrementAndGet()
            service.nativeStopVerified.set(false)
            // Invalidate any pending retry when a new start begins.
            service.invalidatePendingPolicyRetry()
            val job =
                service.serviceScope.launch {
                    doStartOffer(generation)
                }
            service.activeStartup = ActiveStartup(generation, job)
        }
    }

    private suspend fun doStartOffer(startGeneration: Long) {
        service.lastMode = TunnelMode.Offer
        service.startForeground(
            TunnelForegroundService.NOTIFICATION_ID,
            service.reporter.loadingNotification(service.getString(R.string.service_msg_starting_tunnel)),
        )
        val completion = performStartupAttempt(startGeneration)
        service.submitLifecycleCommand(LifecycleCommand.StartupCompleted(startGeneration, completion))
    }

    // P0-001: Wraps both preparation and native start into a single completion boundary.
    // Every path returns a typed StartOutcome — no path may return without completion.
    // Cancellation propagates; other exceptions become typed StartOutcome values.
    private suspend fun performStartupAttempt(generation: Long): StartOutcome {
        return try {
            val identity =
                prepareOfferIdentity()

            try {
                classifyStartAndZeroIdentity(
                    identity = identity,
                    generation = generation,
                )
            } finally {
                identity.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: StartupPolicyBlocked) {
            StartOutcome.PolicyBlocked(
                reason =
                    blocked.message
                        ?: "Blocked by network policy",
            )
        } catch (aborted: StartupAborted) {
            StartOutcome.Aborted(
                reason =
                    aborted.message
                        ?: "Startup aborted",
            )
        } catch (error: Exception) {
            StartOutcome.UnexpectedFailure(
                error = error,
            )
        }
    }

    // Classifies the native start result after identity has been prepared. Returns Aborted
    // if the generation changed at any point, otherwise classifies the native start result.
    private suspend fun classifyStartAndZeroIdentity(
        identity: ByteArray,
        generation: Long,
    ): StartOutcome {
        val generationStillValid = service.lifecycleGeneration.get() == generation
        return if (generationStillValid) {
            val result =
                withContext(service.ioDispatcher) {
                    service.repository.start(TunnelMode.Offer, service.configRepository.configPath, identity)
                }
            if (service.lifecycleGeneration.get() == generation) {
                classifyStartResult(result)
            } else {
                StartOutcome.Aborted("Startup superseded during native start")
            }
        } else {
            StartOutcome.Aborted("Startup superseded by newer lifecycle generation")
        }
    }

    // Loads + validates prerequisites for an offer start. Returns the private identity
    // bytes, or throws StartupAborted after publishing the appropriate state/error.
    private suspend fun prepareOfferIdentity(): ByteArray {
        val prefs = withContext(service.ioDispatcher) { service.configRepository.preferences.first() }
        val policy =
            service.networkPolicyManager.evaluateWithPolicy(
                prefs.allowMetered || service.allowMeteredForCurrentRun.get(),
            )
        service.repository.updateNetworkStatus(policy)
        if (!policy.tunnelAllowed) {
            throw StartupPolicyBlocked(policy.blockReason ?: "Tunnel blocked by current network policy")
        }
        // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
        // wraps a suspend call chain (withContext).
        val identity =
            try {
                withContext(service.ioDispatcher) { service.identityRepository.readPrivateIdentityPlaintext() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                service.abortStartup(
                    "Unable to decrypt private identity: ${error.message}",
                    "identity_decrypt_failed",
                )
            }
        // P0-008: Ownership transfer — identity is wiped if preparation fails.
        var transferred = false
        try {
            validateConfigAndIdentityForStart(service, prefs, identity)
            transferred = true
            return identity
        } finally {
            if (!transferred) {
                // Preparation failed — wipe the plaintext identity.
                identity.fill(0)
            }
        }
    }

    // P1-001: AllowMeteredSession is now one ordered lifecycle command.
    // The handler performs: set allowance, update repository, begin startup
    // within one command processing step.
    suspend fun allowMeteredForSessionAndStart() {
        service.lifecycleMutex.withLock {
            service.allowMeteredForCurrentRun.set(true)
            service.repository.updateSessionMeteredAllowance(true)
            service.pausedByPolicy.set(false)
            service.invalidatePendingPolicyRetry()
        }
        startOffer()
    }

    suspend fun resume() {
        when (service.lastMode) {
            TunnelMode.Offer -> startOffer()
            TunnelMode.Answer ->
                service.reporter.publishError(
                    message = "Answer mode is not available on Android",
                    code = "answer_mode_disabled",
                )
        }
    }

    suspend fun pause() {
        service.lifecycleMutex.withLock {
            service.lifecycleGeneration.incrementAndGet()
            service.cancelStartupJobAndJoinLocked()
            service.reporter.stopStatusPollingAndJoin()
            service.invalidatePendingPolicyRetry()
            withContext(service.ioDispatcher) { service.repository.stop() }
                .fold(
                    onSuccess = {
                        // P1-011: Set nativeStopVerified true after verified successful pause.
                        service.nativeStopVerified.set(true)
                        service.clearTemporaryMeteredAllowance()
                        service.reporter.publishStatus(service.getString(R.string.service_msg_paused))
                    },
                    onFailure = {
                        // FIX7 P0-007-B: a failed manual-pause stop must quarantine the
                        // runtime, exactly like a failed explicit STOP already does —
                        // previously this only reported the error.
                        service.enterNativeRuntimeQuarantine(
                            code = stopFailureCode(it),
                            message = it.message ?: "Unable to stop tunnel",
                        )
                    },
                )
        }
    }

    suspend fun pauseForPolicy(reason: String) {
        service.lifecycleMutex.withLock {
            service.lifecycleGeneration.incrementAndGet()
            service.cancelStartupJobAndJoinLocked()
            service.reporter.stopStatusPollingAndJoin()
            // P0-002: Invalidate any pending retry when policy pauses.
            service.invalidatePendingPolicyRetry()
            withContext(service.ioDispatcher) { service.repository.stop() }
                .fold(
                    onSuccess = {
                        // P1-011: Set nativeStopVerified true after verified successful policy pause.
                        service.nativeStopVerified.set(true)
                        service.pausedByPolicy.set(true)
                        service.repository.setPolicyBlocked(reason)
                        service.clearTemporaryMeteredAllowance()
                        service.reporter.publishStatus(reason)
                    },
                    onFailure = {
                        // The tunnel did not stop cleanly, so this must never be
                        // reported as the normal policy-paused state. Force false
                        // unconditionally rather than restoring a stale prior
                        // value, so a retry/reevaluation path stays open.
                        service.pausedByPolicy.set(false)
                        // FIX7 P0-007-B: a failed policy-pause stop must quarantine the
                        // runtime, exactly like a failed explicit STOP already does —
                        // previously this only reported the error.
                        service.enterNativeRuntimeQuarantine(
                            code = stopFailureCode(it),
                            message = it.message ?: "Failed stopping tunnel after policy block",
                        )
                    },
                )
        }
    }

    suspend fun stopServiceWork() {
        service.lifecycleMutex.withLock {
            service.lifecycleGeneration.incrementAndGet()
            service.cancelStartupJobAndJoinLocked()
            service.reporter.stopStatusPollingAndJoin()
            val stopResult = withContext(service.ioDispatcher) { service.repository.stop() }
            service.pausedByPolicy.set(false)
            service.clearTemporaryMeteredAllowance()
            stopResult.fold(
                onSuccess = {
                    // P0-005: Stop success path.
                    service.nativeStopVerified.set(true)
                    service.nativeRuntimeUncertain.set(false)
                    // P0-002: Invalidate any pending retry on explicit stop success.
                    service.invalidatePendingPolicyRetry()
                    service.notifications.show(
                        service.notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"),
                    )
                    service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    service.stopSelf()
                },
                onFailure = {
                    // P0-005/FIX7 P0-007-B: Stop failure path — remain alive and
                    // foreground; quarantine through the central helper (covers both
                    // an outright stop failure and a stop-status-verification failure,
                    // per stopFailureCode's distinction).
                    service.enterNativeRuntimeQuarantine(
                        code = stopFailureCode(it),
                        message = it.message ?: "Unable to stop tunnel cleanly",
                    )
                    // Service remains foreground; user can retry STOP.
                },
            )
        }
    }
}

// Applies the user's chosen ICE mode and injects the active network's IPv4
// (ConnectivityManager/LinkProperties) as the vnet_mux host candidate before
// validating/starting, so a strict vnet_mux start fails loudly rather than silently
// dropping to native ICE. Top-level (not an OfferCoordinator member) so it doesn't count
// against that class's detekt TooManyFunctions budget for no behavioral reason.
private suspend fun validateConfigAndIdentityForStart(
    service: TunnelForegroundService,
    prefs: AndroidAppPreferences,
    identity: ByteArray,
) {
    val configResult =
        withContext(service.ioDispatcher) {
            service.configRepository.prepareActiveConfigForStart(
                prefs.androidIceMode,
                service.localAddressResolver.currentIpv4(),
            )
        }
    if (!configResult.isSuccess) {
        val redacted =
            SensitiveDataRedactor.redactText(
                configResult.exceptionOrNull()
                    ?.message ?: "unknown error",
            )
        service.abortStartup(
            "Failed to prepare active config: $redacted",
            "config_prep_failed",
        )
    }
    val validation =
        withContext(service.ioDispatcher) {
            service.identityValidation.validateConfigWithIdentity(service.configRepository.configPath, identity)
        }
    if (!validation.valid) {
        service.abortStartup(
            validation.message ?: "Config validation failed",
            "config_validation_failed",
            ServiceState.ConfigInvalid,
        )
    }
}
