package com.phillipchin.webrtctunnel

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.AppInitializationState
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An application whose default-config creation blocks until released, and whose
 * `onCreate()` calls the real async `start()` (not `initialize()`) — exercising the actual
 * production race between a start request and in-flight app initialization.
 *
 * The synchronization latches belong to this Application instance. Robolectric may create and
 * start the configured Application before JUnit invokes `@Before`; global latch references reset
 * from `@Before` can therefore disconnect the initialization coroutine from the latches observed
 * by the test. Instance-owned latches guarantee both sides always use the same synchronization
 * objects regardless of test-runner ordering or build variant.
 */
class BlockingInitTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

    val initializationEntered = CountDownLatch(1)
    val initializationRelease = CountDownLatch(1)

    // FIX8 P2-002 (CI flakiness root-cause): AppDependencies.appScope is a real,
    // process/JVM-lifetime Dispatchers.IO-backed scope (by design — see its own doc comment,
    // "cancelled only at process teardown"). In production there's exactly one Application, so
    // that's harmless; under Robolectric, every test method builds a fresh Application without
    // ever joining the previous one's initialization coroutine, so this test's own
    // ensureDefaultConfig work can still be running (or queued behind other tests' leaked work
    // in the shared IO thread pool) when the NEXT test's onCreate() tries to run — starving it
    // of a worker thread entirely under CI contention (confirmed: raising the entered-latch
    // timeout to 20s did not fix a real CI failure on this exact wait, ruling out "just slow").
    // Exposed here so tearDown() can join it and guarantee full quiescence before the next test.
    var initializationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val bridge = FailableRecordingBridge()
        TunnelForegroundServiceTestHooks.bridge = bridge
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
                configRepository =
                    object : ConfigRepository(this) {
                        override suspend fun ensureDefaultConfig(contents: String): Result<Unit> {
                            initializationEntered.countDown()
                            check(initializationRelease.await(10, TimeUnit.SECONDS)) {
                                "release latch was never counted down"
                            }
                            return super.ensureDefaultConfig(contents)
                        }
                    },
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = identityRepository,
            )
        initializationJob = appDependencies.appInitializationCoordinator.start()
    }
}

/**
 * FIX7 P1-003-C: a start request while app initialization is still genuinely in flight
 * (Initializing) must fail visibly without a native call, exactly like the already-covered
 * Failed case — and once initialization actually completes (Ready), the same request must
 * succeed. Every other test application reaches Ready synchronously before the service is
 * even created, so neither transition was previously exercised.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = BlockingInitTestApplication::class)
class TunnelForegroundServiceInitializationRaceTest {
    private lateinit var controller: ServiceController<TunnelForegroundService>
    private lateinit var service: TunnelForegroundService

    private val testApplication: BlockingInitTestApplication
        get() = service.applicationContext as BlockingInitTestApplication

    @Before
    fun setUp() {
        controller =
            ServiceController.of(
                realIoService(),
                Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
            )
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        // Release any still-blocked ensureDefaultConfig so teardown cannot hang.
        testApplication.initializationRelease.countDown()
        // FIX8 P2-002 (CI flakiness root-cause): join this test's own initialization coroutine
        // before returning, not just unblock it — otherwise it can still be running (or land in
        // the shared Dispatchers.IO queue behind it) when the NEXT test's fresh Application
        // starts its own coroutine, starving that one of a worker thread under a
        // resource-constrained CI runner. 10s bounds a genuine hang (matching
        // ensureDefaultConfig's own 10s release-await) rather than letting teardown hang forever.
        val job = testApplication.initializationJob
        if (job != null) {
            runBlocking { job.join() }
        }
        controller.destroy()
    }

    private fun actionIntent(action: String) =
        Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java).setAction(action)

    // FIX7 P2-001-A: a bounded poll for POSITIVE external-state convergence only (e.g. a
    // StateFlow/bridge counter settling after real async work dispatched on a real thread pool,
    // with no injected completion event to await instead). The race itself is already proven
    // deterministically via CountDownLatch entered/release hooks; this poll only ever waits for
    // eventual settling afterward, never for absence, exactly-once, ordering, or overlap.
    // FIX8 P2-002 (CI flakiness root-cause): this polls a StateFlow/bridge counter updated by a
    // coroutine on a real Dispatchers.Default worker (no TestDispatcher is injected), so the
    // wait is bounded by actual scheduler latency, not just the work itself. 8s was tight enough
    // that a contended CI runner (shared JVM-wide dispatcher pool across Robolectric test
    // classes) hit it at least once in CI while never reproducing locally; 20s keeps the same
    // eventual-only semantics with real margin for that contention.
    private fun waitForCondition(
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun startWhileExactlyInitializingDoesNotCallNative() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        assertTrue(
            "ensureDefaultConfig must have been entered by now",
            // FIX8 P2-002 (CI flakiness root-cause): same real-dispatcher scheduling
            // dependency as waitForCondition above — 20s to match.
            testApplication.initializationEntered.await(20, TimeUnit.SECONDS),
        )
        assertEquals(AppInitializationState.Initializing, deps.appInitializationCoordinator.state.value)

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            "a start request while genuinely Initializing must fail visibly, not silently",
            waitForCondition {
                deps.tunnelRepository.status.value.lastError?.code == "app_initialization_failed"
            },
        )
        assertEquals(
            "a start blocked by in-flight initialization must not attempt a native start",
            0,
            bridge.startOfferCalls,
        )
    }

    @Test
    fun startAfterReadyCallsNative() {
        val deps = (service.applicationContext as HasAppDependencies).deps
        val bridge = TunnelForegroundServiceTestHooks.bridge
        // FIX8 P2-002 (CI flakiness root-cause): same real-dispatcher scheduling dependency as
        // waitForCondition below — 20s to match. This exact wait failed under CI contention
        // (5s was too tight) while every local run stayed clean.
        assertTrue(testApplication.initializationEntered.await(20, TimeUnit.SECONDS))

        testApplication.initializationRelease.countDown()
        assertTrue(
            "initialization must reach Ready once released",
            waitForCondition {
                deps.appInitializationCoordinator.state.value == AppInitializationState.Ready
            },
        )

        controller.withIntent(actionIntent(TunnelForegroundService.ACTION_START_OFFER)).startCommand(0, 1)

        assertTrue(
            "a start request once Ready must actually call native start",
            waitForCondition { bridge.startOfferCalls > 0 },
        )
    }
}
