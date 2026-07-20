package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX7 P0-001: the single cross-feature admission guard (FIX7-INV-009). No Robolectric needed —
 * the coordinator has no Android surface. Barriers use [CompletableDeferred], never real sleeps.
 */
class ConfigurationMutationCoordinatorTest {
    // detekt's InjectDispatcher requires a real dispatcher only ever appear inside a parameter
    // default, never inline at a call site — these two tests need a genuine background thread so
    // the "holder" coroutine's suspension is observable from the test's own coroutine.
    private fun runBlockingOnRealBackgroundDispatcher(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend CoroutineScope.() -> Unit,
    ) = runBlocking(dispatcher, block)

    @Test
    fun busyAdmissionReportsTheActiveOperation() =
        runBlockingOnRealBackgroundDispatcher {
            val coordinator = ConfigurationMutationCoordinator()
            val holderEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val holder =
                launch {
                    coordinator.tryRun(ConfigurationOperation.SetupSave) {
                        holderEntered.complete(Unit)
                        release.await()
                    }
                }
            holderEntered.await()

            val busy = coordinator.tryRun(ConfigurationOperation.ForwardMutation) { error("must not run") }
            assertEquals(ConfigurationAdmission.Busy(ConfigurationOperation.SetupSave), busy)

            release.complete(Unit)
            holder.join()
        }

    @Test
    fun operationFailureReleasesAdmission() =
        runBlocking {
            val coordinator = ConfigurationMutationCoordinator()

            val failure =
                runCatching {
                    coordinator.tryRun(ConfigurationOperation.ConfigImport) {
                        error("boom")
                    }
                }
            assertTrue(failure.isFailure)
            assertEquals(null, coordinator.activeOperationForTest())

            // Admission must be free again — a second operation is admitted, not rejected.
            val admission =
                coordinator.tryRun(ConfigurationOperation.ForwardMutation) { "ok" }
            assertEquals(ConfigurationAdmission.Completed("ok"), admission)
        }

    @Test
    fun operationCancellationReleasesAdmission() =
        runBlockingOnRealBackgroundDispatcher {
            val coordinator = ConfigurationMutationCoordinator()
            val entered = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<Unit>()

            val job =
                async {
                    coordinator.tryRun(ConfigurationOperation.ConfigurationReset) {
                        entered.complete(Unit)
                        neverCompletes.await()
                    }
                }
            entered.await()
            job.cancel()
            val cancellation = runCatching { job.await() }
            assertTrue(cancellation.exceptionOrNull() is CancellationException)
            assertEquals(null, coordinator.activeOperationForTest())

            // Admission must be free again after cancellation.
            val admission = coordinator.tryRun(ConfigurationOperation.SetupSave) { "ok" }
            assertEquals(ConfigurationAdmission.Completed("ok"), admission)
        }

    @Test
    fun fatalErrorReleasesAdmissionAndStillPropagates() =
        runBlocking {
            val coordinator = ConfigurationMutationCoordinator()

            val result =
                runCatching {
                    coordinator.tryRun(ConfigurationOperation.SetupSave) {
                        throw OutOfMemoryError("simulated fatal error")
                    }
                }
            assertTrue(result.exceptionOrNull() is OutOfMemoryError)
            assertEquals(null, coordinator.activeOperationForTest())
        }

    @Test
    fun completedOperationReturnsValue() =
        runBlocking {
            val coordinator = ConfigurationMutationCoordinator()
            val admission = coordinator.tryRun(ConfigurationOperation.ForwardMutation) { 42 }
            assertEquals(ConfigurationAdmission.Completed(42), admission)
            assertFalse(admission !is ConfigurationAdmission.Completed)
        }
}
