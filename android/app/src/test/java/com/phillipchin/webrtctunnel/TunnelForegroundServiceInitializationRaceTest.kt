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
import java.util.concurrent.atomic.AtomicReference

/**
 * FIX7 P1-003-C: deterministic hooks blocking `ensureDefaultConfig` mid-flight so readiness
 * stays observably `Initializing` for as long as a test needs, rather than racing the real
 * (fast, usually-instant) async transition every other test application's synchronous
 * `initialize()` call sidesteps entirely.
 */
object InitializationRaceTestHooks {
    val entered: AtomicReference<CountDownLatch> = AtomicReference(CountDownLatch(1))
    val release: AtomicReference<CountDownLatch> = AtomicReference(CountDownLatch(1))
}

/**
 * An application whose default-config creation blocks until released, and whose
 * `onCreate()` calls the real async `start()` (not `initialize()`) — exercising the actual
 * production race between a start request and in-flight app initialization.
 */
class BlockingInitTestApplication : Application(), HasAppDependencies {
    private lateinit var appDependencies: AppDependencies
    override val deps: AppDependencies
        get() = appDependencies

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
                            InitializationRaceTestHooks.entered.get().countDown()
                            check(InitializationRaceTestHooks.release.get().await(10, TimeUnit.SECONDS)) {
                                "release latch was never counted down"
                            }
                            return super.ensureDefaultConfig(contents)
                        }
                    },
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = identityRepository,
            )
        appDependencies.appInitializationCoordinator.start()
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
    private val controller =
        ServiceController.of(
            realIoService(),
            Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java),
        )
    private lateinit var service: TunnelForegroundService

    @Before
    fun setUp() {
        InitializationRaceTestHooks.entered.set(CountDownLatch(1))
        InitializationRaceTestHooks.release.set(CountDownLatch(1))
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        // Release any still-blocked ensureDefaultConfig so teardown cannot hang.
        InitializationRaceTestHooks.release.get().countDown()
        controller.destroy()
    }

    private fun actionIntent(action: String) =
        Intent(ApplicationProvider.getApplicationContext(), TunnelForegroundService::class.java).setAction(action)

    private fun waitForCondition(
        timeoutMs: Long = 8_000,
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
            InitializationRaceTestHooks.entered.get().await(5, TimeUnit.SECONDS),
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
        assertTrue(InitializationRaceTestHooks.entered.get().await(5, TimeUnit.SECONDS))

        InitializationRaceTestHooks.release.get().countDown()
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
