package com.phillipchin.webrtctunnel

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.data.AppDispatchers
import com.phillipchin.webrtctunnel.data.AppInitializationCoordinator
import com.phillipchin.webrtctunnel.data.AppInitializationState
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.ForwardsRepository
import com.phillipchin.webrtctunnel.data.ForwardsStore
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * FIX6 INV-010 / FIX7 P1-003: `Application.onCreate()` must not run initialization inside
 * `runBlocking`, and none of the classes `AppDependencies` eagerly constructs on the main
 * thread (`ForwardsRepository`, `NetworkPolicyManager`) may perform disk reads or network
 * classification as a side effect of construction — that work is deferred to the first
 * `refresh()`/`evaluateWithPolicy()`/`monitor()` call, all of which already run off the main
 * thread (FIX7 P1-003-B). `AppInitializationCoordinator.start()` similarly must not block the
 * calling thread on config file I/O (FIX7 P1-003-C).
 *
 * The `runBlocking`-absence check is a source-level guard rather than a runtime timing
 * assertion: timing the main thread would be flaky, whereas that invariant is structural —
 * onCreate must hand off to the initialization coordinator, not block. The construction-time
 * checks below are genuine behavioral tests (call-counting fakes), since a real seam exists.
 */
@RunWith(RobolectricTestRunner::class)
class WebRtcTunnelApplicationInitTest {
    private fun applicationSource(): String {
        val path =
            "src/main/java/com/phillipchin/webrtctunnel/WebRtcTunnelApplication.kt"
        val candidates =
            listOf(
                File(path),
                File("app/$path"),
                File(System.getProperty("user.dir"), path),
            )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("WebRtcTunnelApplication.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun applicationOnCreateDoesNotRunBlockingFileIoOnMainThread() {
        val source = applicationSource()
        // Strip comments so the explanatory reference to the old runBlocking approach does
        // not trip the guard.
        val code =
            source.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")

        assertFalse(
            "Application.onCreate must not use runBlocking; initialization is async via " +
                "AppInitializationCoordinator (FIX6 INV-010)",
            code.contains("runBlocking"),
        )
        assertFalse(
            "Application.onCreate must not import runBlocking",
            code.contains("import kotlinx.coroutines.runBlocking"),
        )
    }

    @Test
    fun applicationOnCreateDelegatesToInitializationCoordinator() {
        assertTrue(
            "onCreate must start the initialization coordinator",
            applicationSource().contains("appInitializationCoordinator.start()"),
        )
    }

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) = org.junit.Assert.assertTrue(message, condition)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun applicationOnCreateDoesNotReadForwardsOnMainThread() {
        val calls = AtomicInteger(0)
        val store =
            object : ForwardsStore {
                override fun loadForwardsResult(): Result<List<ForwardConfig>> {
                    calls.incrementAndGet()
                    return Result.success(emptyList())
                }

                override fun saveForwards(forwards: List<ForwardConfig>) = Unit

                override fun validateForwards(forwards: List<ForwardConfig>): String? = null
            }

        ForwardsRepository(store, AppDispatchers())

        assertEquals(
            "constructing ForwardsRepository (as AppDependencies does on the main thread) " +
                "must not read the forwards file",
            0,
            calls.get(),
        )
    }

    @Test
    fun applicationOnCreateDoesNotClassifyNetworkOnMainThread() {
        val calls = AtomicInteger(0)

        NetworkPolicyManager({
            calls.incrementAndGet()
            NetworkType.UnmeteredWifi to false
        })

        assertEquals(
            "constructing NetworkPolicyManager (as AppDependencies does on the main thread) " +
                "must not classify the network",
            0,
            calls.get(),
        )
    }

    @Test
    fun applicationOnCreateDoesNotPerformConfigFileIoOnMainThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository =
            object : ConfigRepository(context) {
                override suspend fun ensureDefaultConfig(contents: String): Result<Unit> {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "release latch was never counted down" }
                    return super.ensureDefaultConfig(contents)
                }
            }
        val scope = CoroutineScope(SupervisorJob() + realIoDispatcher())
        val coordinator = AppInitializationCoordinator(repository, scope, realIoDispatcher())

        // Mirrors Application.onCreate(): construct, then start() — start() must return
        // immediately without waiting for ensureDefaultConfig, which is currently blocked.
        coordinator.start()

        assertTrue(
            "ensureDefaultConfig must actually run (off whatever thread called start())",
            entered.await(5, TimeUnit.SECONDS),
        )
        assertEquals(
            "start() returning must not depend on ensureDefaultConfig completing",
            AppInitializationState.Initializing,
            coordinator.state.value,
        )
        release.countDown()
        scope.cancel()
    }

    // The only Dispatchers.IO reference lives in this parameter default, per the module's
    // InjectDispatcher convention (see inlineTestDispatchers/realIoTestDispatchers elsewhere).
    private fun realIoDispatcher(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) = dispatcher
}
