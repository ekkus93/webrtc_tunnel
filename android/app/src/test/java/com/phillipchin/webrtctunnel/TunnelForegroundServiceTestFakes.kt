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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
}

class TunnelForegroundServiceTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    override fun onCreate() {
        super.onCreate()
        val bridge = FailableRecordingBridge()
        TunnelForegroundServiceTestHooks.bridge = bridge
        val configRepository = ConfigRepository(this)
        val identityRepository =
            IdentityRepository(
                this,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = payload
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
                        com.phillipchin.webrtctunnel.model.NetworkType.UnmeteredWifi to false
                    },
                identityRepository = identityRepository,
            )
        configRepository.ensureDefaultConfig(configRepository.defaultConfigTemplate())
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
    private val blockStartOfferAtomic = AtomicBoolean(false)
    private val startOfferEntered = AtomicReference(CountDownLatch(0))
    private val startOfferRelease = AtomicReference(CountDownLatch(0))
    private val stateRef = AtomicReference(ServiceState.Stopped)

    val startOfferCalls: Int get() = startOfferCallsAtomic.get()
    val stopCalls: Int get() = stopCallsAtomic.get()
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
        if (failNextStopAtomic.compareAndSet(true, false)) {
            return Result.failure(RuntimeException("injected stop failure"))
        }
        state = ServiceState.Stopped
        return Result.success(Unit)
    }

    override fun getStatusJson(): String {
        // Snapshot before blocking: a real native read observes state as of when it
        // began, not as of when it happens to return after being delayed.
        val snapshotState = state
        if (blockStatusJsonRead.compareAndSet(true, false)) {
            statusJsonReadEntered.get().countDown()
            check(statusJsonReadRelease.get().await(5, TimeUnit.SECONDS)) {
                "blocked status JSON read was never released"
            }
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
    ): ValidationResult = ValidationResult(true, null)

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
