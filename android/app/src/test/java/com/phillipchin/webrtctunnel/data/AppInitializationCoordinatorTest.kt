package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * FIX6 P0-001-A + P1-003 (folded, see RESPONSES Q12): default-config creation returns its
 * result, and initialization readiness is explicit rather than a discarded main-thread
 * `runBlocking` side effect.
 */
@RunWith(RobolectricTestRunner::class)
class AppInitializationCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        File(context.filesDir, "config.toml").delete()
    }

    private class FailingConfigRepository(
        context: android.content.Context,
        private val error: Throwable,
    ) : ConfigRepository(context) {
        override suspend fun ensureDefaultConfig(contents: String): Result<Unit> = Result.failure(error)
    }

    private fun coordinatorFor(
        repository: ConfigRepository,
        scope: CoroutineScope,
    ) = AppInitializationCoordinator(
        configRepository = repository,
        scope = scope,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun readinessStartsAsInitializing() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val coordinator = coordinatorFor(ConfigRepository(context), scope)
        assertEquals(AppInitializationState.Initializing, coordinator.state.value)
        scope.cancel()
    }

    @Test
    fun successfulDefaultConfigCreationProducesReady() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val coordinator = coordinatorFor(ConfigRepository(context), scope)

            coordinator.initialize()

            assertEquals(AppInitializationState.Ready, coordinator.state.value)
            scope.cancel()
        }

    @Test
    fun defaultConfigFailureProducesFailedReadinessWithVisibleCode() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val coordinator =
                coordinatorFor(
                    FailingConfigRepository(context, IOException("disk full")),
                    scope,
                )

            coordinator.initialize()

            val state = coordinator.state.value
            assertTrue("initialization failure must be visible, not silent", state is AppInitializationState.Failed)
            assertEquals("config_initialization_failed", (state as AppInitializationState.Failed).code)
            scope.cancel()
        }

    @Test
    fun defaultConfigFailureMessageIsRedacted() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val coordinator =
                coordinatorFor(
                    FailingConfigRepository(context, IOException("write failed password=hunter2")),
                    scope,
                )

            coordinator.initialize()

            val state = coordinator.state.value as AppInitializationState.Failed
            assertFalse("a raw secret must not reach readiness state", state.message.contains("hunter2"))
            assertTrue(state.message.contains("***REDACTED***"))
            scope.cancel()
        }
}
