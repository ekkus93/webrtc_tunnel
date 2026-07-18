package com.phillipchin.webrtctunnel.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.AppDispatchers
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A TunnelNativeBridge double that records status reads and returns a configurable validation result. */
class RecordingBridge : TunnelNativeBridge {
    private val statusReadsAtomic = AtomicInteger(0)
    val statusReads: Int get() = statusReadsAtomic.get()
    var validationResult: ValidationResult = ValidationResult(true, null)

    // Override the canned identity-validation/generation results below when a test needs a
    // failure path; null (the default) preserves the existing always-succeeds behavior so no
    // other test is affected.
    var privateIdentityValidationResult: IdentityValidationResult? = null
    var publicIdentityValidationResult: IdentityValidationResult? = null
    var generateIdentityResult: IdentityValidationResult? = null

    // Overrides the canned "[]" recent-logs response below when a test needs to feed
    // LogsViewModel.refresh() specific log events.
    var recentLogsJson: String = "[]"

    // Tracks which config-validation entry point callers actually used, so a test can
    // prove identity-aware vs. identity-absent validation was chosen (P1-001) instead of
    // only observing the (identical) canned ValidationResult either path returns.
    // Backed by atomics (P0-004): a validation barrier below is driven by a genuine
    // Dispatchers.IO caller concurrently with the Robolectric test thread, so a plain
    // `var` here would be a required test relying on accidental JVM visibility.
    private val validateConfigCallsAtomic = AtomicInteger(0)
    private val validateConfigWithIdentityCallsAtomic = AtomicInteger(0)
    val validateConfigCalls: Int get() = validateConfigCallsAtomic.get()
    val validateConfigWithIdentityCalls: Int get() = validateConfigWithIdentityCallsAtomic.get()

    // P0-004: deterministic barrier so a test can block config validation mid-flight —
    // after mutation persistence has already succeeded but before the sync result is
    // known — to exercise the rollback-persistence-failure path without any production
    // test hook. The forced result (if any) is what the blocked call returns once
    // released, standing in for whatever validation outcome the test needs.
    private val blockValidateConfigAtomic = AtomicBoolean(false)
    private val validateConfigEntered = AtomicReference(CountDownLatch(0))
    private val validateConfigRelease = AtomicReference(CountDownLatch(0))
    private val forcedValidateConfigResult = AtomicReference<ValidationResult?>(null)

    /** The next `validateConfig`/`validateConfigWithIdentity` call blocks until
     * [releaseBlockedValidateConfig] is called. */
    fun blockNextValidateConfig() {
        validateConfigEntered.set(CountDownLatch(1))
        validateConfigRelease.set(CountDownLatch(1))
        blockValidateConfigAtomic.set(true)
    }

    /** Non-blocking peek so a caller pumping a Robolectric main-looper queue (needed to
     * carry a coroutine across a real-dispatcher suspension point) can poll for entry
     * instead of blocking the only thread that can drain that queue. */
    fun validateConfigEnteredNow(): Boolean = validateConfigEntered.get().count == 0L

    /** Releases a blocked validation call, forcing it to return [result]. */
    fun releaseBlockedValidateConfig(result: ValidationResult) {
        forcedValidateConfigResult.set(result)
        validateConfigRelease.get().countDown()
    }

    private fun validationResultAfterOptionalBlock(): ValidationResult {
        if (blockValidateConfigAtomic.compareAndSet(true, false)) {
            validateConfigEntered.get().countDown()
            check(validateConfigRelease.get().await(5, TimeUnit.SECONDS)) {
                "blocked validateConfig was never released"
            }
            forcedValidateConfigResult.getAndSet(null)?.let { return it }
        }
        return validationResult
    }

    override fun startOffer(
        configPath: String,
        identityBytes: ByteArray?,
    ): Result<Unit> = Result.success(Unit)

    override fun startAnswer(configPath: String): Result<Unit> = Result.success(Unit)

    override fun stop(): Result<Unit> = Result.success(Unit)

    override fun getStatusJson(): String {
        statusReadsAtomic.incrementAndGet()
        return kotlinx.serialization.json.Json.encodeToString(
            NativeRuntimeStatusDto.serializer(),
            NativeRuntimeStatusDto(state = "stopped", mode = "offer"),
        )
    }

    override fun getRecentLogsJson(maxEvents: Int): String = recentLogsJson

    override fun validateConfig(configPath: String): ValidationResult {
        validateConfigCallsAtomic.incrementAndGet()
        return validationResultAfterOptionalBlock()
    }

    // FIX6 regression seam: a filesystem-aware validation hook so a test can mirror the native
    // validator's requirement that the config's referenced files (e.g. authorized_keys) exist at
    // validation time. When set, it replaces the canned result.
    var validateConfigWithIdentityHook: (() -> ValidationResult)? = null

    override fun validateConfigWithIdentity(
        configPath: String,
        identityBytes: ByteArray,
    ): ValidationResult {
        validateConfigWithIdentityCallsAtomic.incrementAndGet()
        validateConfigWithIdentityHook?.let { return it() }
        return validationResultAfterOptionalBlock()
    }

    override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
        privateIdentityValidationResult ?: IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "canon",
            canonicalPrivateIdentity = identityToml,
            peerId = "android-phone",
        )

    override fun validatePublicIdentity(line: String): IdentityValidationResult =
        publicIdentityValidationResult ?: IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = line.trim(),
            peerId = "remote-peer",
        )

    override fun generateIdentity(peerId: String): IdentityValidationResult =
        generateIdentityResult ?: IdentityValidationResult(
            valid = true,
            canonicalPublicIdentity = "canon",
            canonicalPrivateIdentity = "private",
            peerId = peerId,
        )
}

/** Shared Robolectric fixture for the per-ViewModel test classes. */
open class AppViewModelTestBase {
    protected val app: Application = ApplicationProvider.getApplicationContext()
    protected lateinit var configRepository: ConfigRepository
    protected lateinit var recordingBridge: RecordingBridge
    protected lateinit var tunnelRepository: TunnelRepository
    protected lateinit var deps: AppDependencies

    @Before
    open fun setUp() {
        configRepository = ConfigRepository(app)
        recordingBridge = RecordingBridge()
        deps = createTestDeps(configRepository = configRepository)
        tunnelRepository = deps.tunnelRepository
    }

    /**
     * Create AppDependencies for tests.
     * Subclasses can override this to inject custom dependencies.
     */
    protected open fun createTestDeps(configRepository: ConfigRepository): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = configRepository,
            networkPolicyManager =
                NetworkPolicyManager {
                    NetworkType.UnmeteredWifi to false
                },
            identityRepository =
                IdentityRepository(
                    app,
                    object : IdentityCrypto {
                        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                        override fun decrypt(payload: ByteArray): ByteArray = payload
                    },
                ),
            // Run all ViewModel IO inline so coroutine results are observable
            // synchronously in tests (no real thread hops).
            dispatchers = inlineTestDispatchers(),
        )
}

/** Test dispatchers that execute inline. The `Dispatchers.Unconfined` default keeps the
 * only direct reference inside a parameter default (DI), satisfying `InjectDispatcher`. */
fun inlineTestDispatchers(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): AppDispatchers =
    AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher)

/** Real (non-inline) dispatchers for tests that need a genuine suspension point — e.g.
 * observing a busy-guard mid-flight, which an always-synchronous inline dispatcher would
 * never leave a window for. The `Dispatchers.IO` default keeps the only direct reference
 * inside a parameter default (DI), satisfying `InjectDispatcher`. */
fun realIoTestDispatchers(dispatcher: CoroutineDispatcher = Dispatchers.IO): AppDispatchers =
    AppDispatchers(io = dispatcher, default = dispatcher)
