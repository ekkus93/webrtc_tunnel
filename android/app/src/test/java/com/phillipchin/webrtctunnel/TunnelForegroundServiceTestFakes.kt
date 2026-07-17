package com.phillipchin.webrtctunnel

import android.app.Application
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * P0-007: Deterministic lifecycle event recording for event-order proofs.
 * Replaces elapsed-time absence checks (withTimeoutOrNull/Thread.sleep) with
 * explicit event ordering that does not depend on timing.
 */
internal sealed interface FakeLifecycleEvent {
    data object StatusReadEntered : FakeLifecycleEvent

    data object StatusReadReleased : FakeLifecycleEvent

    data class StopEntered(val call: Int) : FakeLifecycleEvent
}

/**
 * A `test`-only-scoped Application/bridge fake for
 * [TunnelForegroundServiceStopFailureTest]. Deliberately not shared with
 * `androidTest`'s [com.phillipchin.webrtctunnel.TestWebRtcTunnelApplication]/
 * `RecordingBridge` via a shared source set: doing so was tried first (P0-003/
 * P0-004) and empirically caused unrelated Robolectric `viewmodel` tests
 * elsewhere in this module to fail when run in the same JVM, for reasons not
 * pinned down further. A small, self-contained duplicate avoids that risk.
 */
object TunnelForegroundServiceTestHooks {
    lateinit var bridge: FailableRecordingBridge

    // P0-001: Failure injection points for startup preparation tests.
    @get:JvmName("identityReadFailureMessage")
    val identityReadFailure: AtomicReference<String?> = AtomicReference(null)

    @get:JvmName("configPrepFailureMessage")
    val configPrepFailure: AtomicReference<String?> = AtomicReference(null)

    @get:JvmName("policyBlockReason")
    val policyBlockReason: AtomicReference<String?> = AtomicReference(null)

    @get:JvmName("configValidationFailureMessage")
    val configValidationFailure: AtomicReference<String?> = AtomicReference(null)

    // P0-001: Inject an unexpected exception during validateConfigWithIdentity
    // (throws instead of returning ValidationResult) to exercise the
    // catch (error: Exception) → StartOutcome.UnexpectedFailure path.
    @get:JvmName("validationThrows")
    val validationThrows: AtomicReference<String?> = AtomicReference(null)

    // P0-003: Inject an exception thrown during config preparation (instead of
    // returning a failed Result) to exercise the Throwable-catch path in
    // writeConfigAtomicallyLocked.
    @get:JvmName("configPrepThrows")
    val configPrepThrows: AtomicReference<String?> = AtomicReference(null)

    // P1-002: Inject a preferences-read failure/cancellation for
    // handlePolicyAllowed() diagnostic-coverage tests. configRepository.preferences is
    // read by several independent callers (the network monitor loop, prepareOfferIdentity,
    // handlePolicyAllowed) — preferenceReadInterceptSkipCount lets a test let the first N
    // reads succeed normally and only fail/cancel the specific read it's targeting.
    @get:JvmName("preferenceReadFailure")
    val preferenceReadFailure: AtomicReference<String?> = AtomicReference(null)

    @get:JvmName("preferenceReadCancels")
    val preferenceReadCancels: AtomicBoolean = AtomicBoolean(false)

    @get:JvmName("preferenceReadInterceptSkipCount")
    val preferenceReadInterceptSkipCount: AtomicInteger = AtomicInteger(0)
}

/**
 * P1-002: decides whether a `ConfigRepository.preferences` read should be intercepted
 * (failed/cancelled) or passed through to [real]. `configRepository.preferences` is read
 * by several independent callers (the network monitor loop, prepareOfferIdentity,
 * handlePolicyAllowed), so [TunnelForegroundServiceTestHooks.preferenceReadInterceptSkipCount]
 * lets a test allow the first N reads through and only intercept the one it's targeting.
 * Top-level (not nested inside `TunnelForegroundServiceTestApplication.onCreate()`) so
 * that onCreate stays under detekt's LongMethod/ReturnCount thresholds.
 */
private fun interceptPreferenceReadHook(
    real: kotlinx.coroutines.flow.Flow<AndroidAppPreferences>,
): kotlinx.coroutines.flow.Flow<AndroidAppPreferences> {
    val hooks = TunnelForegroundServiceTestHooks
    val armed = hooks.preferenceReadCancels.get() || hooks.preferenceReadFailure.get() != null
    val skipping = armed && hooks.preferenceReadInterceptSkipCount.get() > 0
    if (skipping) {
        hooks.preferenceReadInterceptSkipCount.decrementAndGet()
    }
    val failure = hooks.preferenceReadFailure.get()
    return when {
        skipping -> real
        hooks.preferenceReadCancels.get() ->
            kotlinx.coroutines.flow.flow {
                throw kotlinx.coroutines.CancellationException("injected preference read cancellation")
            }
        failure != null -> kotlinx.coroutines.flow.flow { throw java.io.IOException(failure) }
        else -> real
    }
}

class TunnelForegroundServiceTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        val bridge = FailableRecordingBridge()
        TunnelForegroundServiceTestHooks.bridge = bridge
        // P0-001: Wire config preparation failure injection hook.
        val configRepository =
            object : ConfigRepository(this) {
                // P1-002: preferences-read failure/cancellation injection (see
                // interceptPreferenceReadHook for the actual decision logic, kept
                // top-level to keep this onCreate() under detekt's LongMethod/ReturnCount
                // thresholds).
                override val preferences: kotlinx.coroutines.flow.Flow<AndroidAppPreferences>
                    get() = interceptPreferenceReadHook(super.preferences)

                override suspend fun prepareActiveConfigForStart(
                    iceMode: String,
                    advertisedIpv4: String?,
                ): Result<Unit> {
                    // P0-003: Check for injected exception throw during config preparation.
                    val throws = TunnelForegroundServiceTestHooks.configPrepThrows.get()
                    if (throws != null) {
                        TunnelForegroundServiceTestHooks.configPrepThrows.set(null)
                        throw java.io.IOException(throws)
                    }
                    // Check for injected config preparation failure.
                    val failure = TunnelForegroundServiceTestHooks.configPrepFailure.get()
                    if (failure != null) {
                        TunnelForegroundServiceTestHooks.configPrepFailure.set(null)
                        return Result.failure(java.io.IOException(failure))
                    }
                    return super.prepareActiveConfigForStart(iceMode, advertisedIpv4)
                }
            }
        val identityRepository =
            IdentityRepository(
                this,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray {
                        // P0-001: Failure injection for identity read tests.
                        val failure = TunnelForegroundServiceTestHooks.identityReadFailure.get()
                        if (failure != null) {
                            throw java.io.IOException(failure)
                        }
                        return payload
                    }
                },
            )
        identityRepository.storeEncryptedIdentity(
            """
            [identity]
            peer_id = "android-phone"
            signing_key = "test-signing-key"
            kex_secret = "test-kex-secret"
            """.trimIndent().toByteArray(),
            "android-phone ssh-ed25519 AAAA test",
        )
        appDependencies =
            AppDependencies(
                context = this,
                nativeBridgeFactory = { bridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        // P0-001: Check for injected policy block.
                        val blockReason = TunnelForegroundServiceTestHooks.policyBlockReason.get()
                        if (blockReason != null) {
                            com.phillipchin.webrtctunnel.model.NetworkType.NoNetwork to false
                        } else {
                            // Default: UnmeteredWifi, metered not allowed, tunnel allowed.
                            com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi to false
                        }
                    },
                identityRepository = identityRepository,
            )
        // FIX6 INV-010: drive initialization to a terminal Ready state inline. The service
        // now gates start on readiness, and initialize() (rather than start()) keeps that
        // deterministic instead of racing a launched coroutine.
        runBlocking { appDependencies.appInitializationCoordinator.initialize() }
        // Pin resumeOnUnmetered = false regardless of any residual preference left on
        // disk by an earlier Robolectric test sharing this JVM's real DataStore file:
        // the fake NetworkPolicyManager below always reports UnmeteredWifi, so leaving
        // this at its true default would race the service's own auto-resume-on-unmetered
        // feature against this test's direct pauseForPolicy() calls.
        runBlocking { configRepository.savePreferences(AndroidAppPreferences(resumeOnUnmetered = false)) }
    }
}

/**
 * Minimal native-bridge fake: only `stop()` is exercised (with an injectable one-shot
 * failure); every other call just reports an idle/stopped state.
 *
 * Required tests now drive this fake with real `Dispatchers.IO` concurrently with the
 * Robolectric test thread, so every field here is a thread-safe primitive rather than
 * a plain `var` (P0-002) — a plain field would be a required test relying on
 * accidental JVM visibility, which the release-signoff hardening pass forbids.
 */
class FailableRecordingBridge : TunnelNativeBridge {
    private val startOfferCallsAtomic = AtomicInteger(0)
    private val stopCallsAtomic = AtomicInteger(0)
    private val failNextStopAtomic = AtomicBoolean(false)
    private val failNextStartOfferAtomic = AtomicBoolean(false)
    private val blockStartOfferAtomic = AtomicBoolean(false)
    private val startOfferEntered = AtomicReference(CountDownLatch(0))
    private val startOfferRelease = AtomicReference(CountDownLatch(0))
    private val stateRef = AtomicReference(ServiceState.Stopped)

    val startOfferCalls: Int get() = startOfferCallsAtomic.get()
    val stopCalls: Int get() = stopCallsAtomic.get()

    // P0-007: Thread-safe event list for deterministic lifecycle event ordering.
    private val lifecycleEvents = CopyOnWriteArrayList<FakeLifecycleEvent>()

    internal fun lifecycleEventsSnapshot(): List<FakeLifecycleEvent> = lifecycleEvents.toList()

    var state: ServiceState
        get() = stateRef.get()
        set(value) = stateRef.set(value)

    // P0-001: deterministic barrier for a status refresh blocked mid-read, built with
    // thread-safe primitives from the start since it's exercised by a real
    // Dispatchers.IO caller concurrently with the Robolectric test thread.
    private val blockStatusJsonRead = AtomicBoolean(false)
    private val statusJsonReadEntered = AtomicReference(CountDownLatch(0))
    private val statusJsonReadRelease = AtomicReference(CountDownLatch(0))

    // P0-005: lets a test wait for the exact moment a stop() call is entered, rather
    // than inferring it happened from stopCalls reaching some count at some later,
    // arbitrary point in time. Unbounded/unlimited: a test-only channel with a single,
    // always-draining reader per test method, so this never needs to apply backpressure.
    private val stopCallEvents = Channel<Int>(Channel.UNLIMITED)

    /** Suspends until the next `stop()` call is entered, returning that call's 1-based
     * ordinal. Fails loudly (via `withTimeout`) rather than silently continuing if no
     * such call ever happens. */
    suspend fun awaitStopCall(timeoutMs: Long = 10_000): Int = withTimeout(timeoutMs) { stopCallEvents.receive() }

    /** The next (and only the next) `stop()` call fails instead of succeeding. */
    fun failNextStop() {
        failNextStopAtomic.set(true)
    }

    /** The next (and only the next) `startOffer()` call fails instead of succeeding. */
    fun failNextStartOffer() {
        failNextStartOfferAtomic.set(true)
    }

    /** The next `startOffer()` call blocks until [releaseBlockedStartOffer] is called. */
    fun blockNextStartOffer() {
        startOfferEntered.set(CountDownLatch(1))
        startOfferRelease.set(CountDownLatch(1))
        blockStartOfferAtomic.set(true)
    }

    fun awaitStartOfferEntered(timeoutMs: Long): Boolean =
        startOfferEntered.get().await(timeoutMs, TimeUnit.MILLISECONDS)

    fun releaseBlockedStartOffer() {
        startOfferRelease.get().countDown()
    }

    /**
     * The next `getStatusJson()` call blocks (mid native-status-read) until
     * [releaseBlockedStatusJsonRead] is called, reporting whatever [state] was at
     * the moment the read began — simulating a real native read that started
     * before a concurrent stop/pause changed the underlying state.
     */
    fun blockNextStatusJsonRead() {
        statusJsonReadEntered.set(CountDownLatch(1))
        statusJsonReadRelease.set(CountDownLatch(1))
        blockStatusJsonRead.set(true)
    }

    fun awaitStatusJsonReadEntered(timeoutMs: Long): Boolean =
        statusJsonReadEntered.get().await(timeoutMs, TimeUnit.MILLISECONDS)

    fun releaseBlockedStatusJsonRead() {
        statusJsonReadRelease.get().countDown()
    }

    private val forceNextStatusJsonErrorAtomic = AtomicBoolean(false)

    /** The next `getStatusJson()` call reports state `"error"` regardless of [state] —
     * simulating a native stop that returned success but whose post-stop status read
     * observes the runtime did not actually reach a clean stopped state (P0-003). */
    fun forceNextStatusJsonToReportError() {
        forceNextStatusJsonErrorAtomic.set(true)
    }

    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> {
        startOfferCallsAtomic.incrementAndGet()
        if (blockStartOfferAtomic.compareAndSet(true, false)) {
            startOfferEntered.get().countDown()
            check(startOfferRelease.get().await(5, TimeUnit.SECONDS)) {
                "blocked startOffer was never released"
            }
        }
        if (failNextStartOfferAtomic.compareAndSet(true, false)) {
            return Result.failure(RuntimeException("injected start failure"))
        }
        state = ServiceState.Connected
        return Result.success(Unit)
    }

    override fun startAnswer(configPath: String): Result<Unit> {
        state = ServiceState.Serving
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        val call = stopCallsAtomic.incrementAndGet()
        check(stopCallEvents.trySend(call).isSuccess) {
            "stop-call observer unexpectedly closed"
        }
        // P0-007: Record the stop event for deterministic ordering proofs.
        lifecycleEvents.add(FakeLifecycleEvent.StopEntered(call))
        if (failNextStopAtomic.compareAndSet(true, false)) {
            return Result.failure(RuntimeException("injected stop failure"))
        }
        state = ServiceState.Stopped
        return Result.success(Unit)
    }

    override fun getStatusJson(): String {
        // P0-007: Record status read entry for deterministic ordering proofs.
        lifecycleEvents.add(FakeLifecycleEvent.StatusReadEntered)
        // Snapshot before blocking: a real native read observes state as of when it
        // began, not as of when it happens to return after being delayed.
        val snapshotState = state
        if (blockStatusJsonRead.compareAndSet(true, false)) {
            statusJsonReadEntered.get().countDown()
            check(statusJsonReadRelease.get().await(5, TimeUnit.SECONDS)) {
                "blocked status JSON read was never released"
            }
        }
        // P0-007: Record status read release for deterministic ordering proofs.
        lifecycleEvents.add(FakeLifecycleEvent.StatusReadReleased)
        if (forceNextStatusJsonErrorAtomic.compareAndSet(true, false)) {
            return Json.encodeToString(
                NativeRuntimeStatusDto(state = "error", mode = "offer", active = false),
            )
        }
        return Json.encodeToString(
            NativeRuntimeStatusDto(
                state =
                    when (snapshotState) {
                        ServiceState.Connected, ServiceState.Serving -> "running"
                        ServiceState.Starting -> "starting"
                        ServiceState.Stopping -> "stopping"
                        ServiceState.Error -> "error"
                        else -> "stopped"
                    },
                mode = if (snapshotState == ServiceState.Serving) "answer" else "offer",
                active = snapshotState == ServiceState.Connected || snapshotState == ServiceState.Serving,
            ),
        )
    }

    override fun getRecentLogsJson(maxEvents: Int): String = "[]"

    override fun validateConfig(configPath: String): ValidationResult = ValidationResult(true, null)

    override fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult {
        // P0-001: Unexpected exception injection (throws, not returns).
        val throws = TunnelForegroundServiceTestHooks.validationThrows.get()
        if (throws != null) {
            TunnelForegroundServiceTestHooks.validationThrows.set(null)
            throw java.io.IOException(throws)
        }

        // P0-001: Failure injection for config validation tests.
        val failure = TunnelForegroundServiceTestHooks.configValidationFailure.get()
        if (failure != null) {
            TunnelForegroundServiceTestHooks.configValidationFailure.set(null)
            return ValidationResult(false, failure)
        }
        return ValidationResult(true, null)
    }

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "android-phone ssh-ed25519 AAAA test",
            canonicalPrivateIdentity = identityToml,
            peerId = "android-phone",
        )

    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = line.isNotBlank(),
            message = if (line.isBlank()) "empty" else null,
            canonicalPublicIdentity = line.trim(),
            peerId = "desktop-peer",
        )

    override fun generateIdentity(peerId: String): IdentityValidationResult =
        IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "$peerId ssh-ed25519 AAAA generated",
            canonicalPrivateIdentity = "[identity]\npeer_id = \"$peerId\"\n",
            peerId = peerId,
        )
}

/**
 * Creates a [TunnelForegroundService] with real IO dispatchers for service-level
 * tests that need coroutine pumping (Unconfined doesn't pump back to the test
 * thread for `launch { ... }` work).
 *
 * The `Dispatchers.IO` default keeps the only direct reference inside a
 * parameter default (DI), satisfying `InjectDispatcher` — see this
 * project's `inlineTestDispatchers()`/`realIoTestDispatchers()` convention
 * in `AppViewModelTestBase.kt`.
 */
fun realIoService(dispatcher: CoroutineDispatcher = Dispatchers.IO): TunnelForegroundService =
    TunnelForegroundService(ioDispatcher = dispatcher, defaultDispatcher = dispatcher)
