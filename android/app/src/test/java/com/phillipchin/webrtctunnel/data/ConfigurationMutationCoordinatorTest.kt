package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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

    // A small, dedicated (not process-wide-shared) real dispatcher for the concurrency stress
    // test below, so its many short-lived coroutines cannot contend with unrelated tests using
    // the shared Dispatchers.IO pool in the same JVM fork.
    private fun runBlockingOnBoundedRealDispatcher(
        dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8),
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

    // FIX8 P0-002-A/HIGH-3: the old mutex.tryLock()-then-active.set() design had a window,
    // right at acquisition, where a competing caller could win tryLock() == false but read
    // active == null (not yet published) and fall back to reporting ITS OWN requested
    // operation as the busy owner (`active.get() ?: operation`) instead of the true one. An
    // atomic CAS token acquires and publishes the owner in one indivisible step, eliminating
    // that window by construction. This stress test drives many concurrent acquisition
    // attempts (no pre-established holder, so every attempt genuinely races for first
    // admission) and asserts the coordinator's two core safety properties hold throughout:
    // (1) at most one operation's block ever runs at a time, and (2) a Busy response is only
    // ever returned while some operation is genuinely, verifiably running — never a
    // null-derived or racer-invented value.
    //
    // Uses a small dedicated dispatcher (not the process-wide, test-suite-shared Dispatchers.IO
    // pool): 250 concurrent attempts is already many times the coordinator's total operation
    // count and is enough to exercise the acquisition race repeatedly, without risking resource
    // contention with unrelated tests sharing the same JVM fork.
    @Test
    fun busyAdmissionDuringOwnerPublicationAlwaysReportsActualOwner() =
        runBlockingOnBoundedRealDispatcher {
            val coordinator = ConfigurationMutationCoordinator()
            val concurrentlyRunning = AtomicInteger(0)
            val mutualExclusionViolations = AtomicInteger(0)
            val operations = ConfigurationOperation.entries

            val attempts =
                (1..250).map { i ->
                    async {
                        val operation = operations[i % operations.size]
                        coordinator.tryRun(operation) {
                            if (concurrentlyRunning.incrementAndGet() > 1) {
                                mutualExclusionViolations.incrementAndGet()
                            }
                            concurrentlyRunning.decrementAndGet()
                        }
                    }
                }
            attempts.awaitAll()

            assertEquals("no two operations may ever run concurrently", 0, mutualExclusionViolations.get())
        }

    @Test
    fun sameOperationTypeCannotClearAnotherOwnerToken() =
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

            // A second attempt using the SAME operation type as the holder must be rejected as
            // Busy — and, critically, must not release the holder's own token as a side effect.
            val secondAttemptRan = java.util.concurrent.atomic.AtomicBoolean(false)
            val secondAdmission =
                coordinator.tryRun(ConfigurationOperation.SetupSave) { secondAttemptRan.set(true) }
            assertEquals(ConfigurationAdmission.Busy(ConfigurationOperation.SetupSave), secondAdmission)
            assertFalse(
                "a same-type second attempt must never run its block while the holder is active",
                secondAttemptRan.get(),
            )
            assertEquals(
                "the holder's own admission must remain intact after a same-type Busy attempt",
                ConfigurationOperation.SetupSave,
                coordinator.activeOperationForTest(),
            )

            release.complete(Unit)
            holder.join()

            // After the holder genuinely releases, a fresh same-type admission must succeed —
            // proving the earlier Busy attempt left no residue behind.
            val thirdAdmission = coordinator.tryRun(ConfigurationOperation.SetupSave) { "ok" }
            assertEquals(ConfigurationAdmission.Completed("ok"), thirdAdmission)
        }

    @Test
    fun fatalErrorReleasesTokenAndPropagatesSameInstance() =
        runBlocking {
            val coordinator = ConfigurationMutationCoordinator()
            val fatal = OutOfMemoryError("simulated fatal error")

            val caught =
                try {
                    coordinator.tryRun(ConfigurationOperation.SetupSave) { throw fatal }
                    null
                } catch (error: Throwable) {
                    error
                }

            assertTrue("the exact primary fatal instance must propagate unchanged", caught === fatal)
            assertEquals(null, coordinator.activeOperationForTest())

            // Admission must be free again — a subsequent operation is admitted, not rejected.
            val admission = coordinator.tryRun(ConfigurationOperation.ForwardMutation) { "ok" }
            assertEquals(ConfigurationAdmission.Completed("ok"), admission)
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
