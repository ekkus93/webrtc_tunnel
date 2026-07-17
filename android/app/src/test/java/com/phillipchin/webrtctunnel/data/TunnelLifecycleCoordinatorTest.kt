package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-003: TunnelLifecycleCoordinator.processCommand() must catch unexpected exceptions
 * visibly (not just the previously-listed IllegalArgumentException/IllegalStateException/
 * IOException) without silently killing the command processor, while cancellation must
 * still propagate and stop it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelLifecycleCoordinatorTest {
    private class RecordingCoordinatorOperations : CoordinatorOperations {
        val errors = CopyOnWriteArrayList<Pair<String, String>>()
        val handled = CopyOnWriteArrayList<String>()
        val startOfferCalls = AtomicInteger(0)
        var startOfferThrows: Throwable? = null

        override fun onError(
            message: String,
            code: String,
            state: ServiceState,
        ) {
            errors.add(message to code)
        }

        override suspend fun startOffer() {
            handled.add("startOffer")
            startOfferCalls.incrementAndGet()
            startOfferThrows?.let { throw it }
        }

        override suspend fun pause() {
            handled.add("pause")
        }

        override suspend fun resume() {
            handled.add("resume")
        }

        override suspend fun stop() {
            handled.add("stop")
        }

        override suspend fun allowMeteredForSessionAndStart() {
            handled.add("allowMetered")
        }

        override suspend fun pauseForPolicy(reason: String) {
            handled.add("pauseForPolicy")
        }

        override suspend fun handlePolicyAllowed() {
            handled.add("policyAllowed")
        }

        override suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
            handled.add("retryPolicyResume")
        }

        override suspend fun handleStartupCompleted(
            generation: Long,
            outcome: StartOutcome,
        ) {
            handled.add("startupCompleted")
        }
    }

    @Test
    fun unexpectedExceptionPublishesLifecycleCommandFailed() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = NullPointerException("unexpected handler bug")
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertEquals(1, ops.errors.size)
            assertEquals("lifecycle_command_failed", ops.errors.single().second)
            coordinator.stop()
        }

    @Test
    fun processorContinuesWithLaterCommandAfterUnexpectedException() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = NullPointerException("unexpected handler bug")
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()
            assertEquals(1, ops.errors.size)

            // The handler no longer throws — a later command must still be processed,
            // proving the unexpected exception did not kill the processor loop.
            ops.startOfferThrows = null
            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertEquals(2, ops.startOfferCalls.get())
            assertEquals(
                "the second, successful call must not add another error",
                1,
                ops.errors.size,
            )
            coordinator.stop()
        }

    @Test
    fun cancellationExceptionFromHandlerStillStopsProcessorAndIsNotReportedAsFailure() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = CancellationException("propagated cancellation")
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertTrue(
                "cancellation must propagate, not be converted into a lifecycle_command_failed error",
                ops.errors.isEmpty(),
            )

            // The processor coroutine must have died from the propagated cancellation —
            // a later command sits in the (still-open) channel with no active consumer.
            val callsAfterCancellation = ops.startOfferCalls.get()
            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()
            assertEquals(
                "no later command may be processed once the processor has been killed by cancellation",
                callsAfterCancellation,
                ops.startOfferCalls.get(),
            )
            coordinator.stop()
        }

    @Test
    fun commandsAreProcessedInTheOrderTheyWereSubmitted() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            // Submitting inline (rather than a launched coroutine per command) is what makes
            // the enqueue order deterministic, which is the whole point of the ordered queue:
            // a later intent must never overtake an earlier one.
            coordinator.trySubmit(LifecycleCommand.StartOffer)
            coordinator.trySubmit(LifecycleCommand.Pause)
            coordinator.trySubmit(LifecycleCommand.Resume)
            coordinator.trySubmit(LifecycleCommand.Stop)
            advanceUntilIdle()

            assertEquals(
                listOf("startOffer", "pause", "resume", "stop"),
                ops.handled.toList(),
            )
            coordinator.stop()
        }

    @Test
    fun submitAfterStopIsReportedAsDroppedRatherThanThrowing() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()
            coordinator.stop()

            // onDestroy stops the coordinator (closing the channel) *before* it cancels an
            // in-flight startup, so that startup's StartupCompleted can still be submitted
            // afterwards. That must be a benign false — a suspending send() would instead
            // throw ClosedSendChannelException into a detached coroutine and crash the app.
            val accepted =
                coordinator.trySubmit(
                    LifecycleCommand.StartupCompleted(generation = 1, outcome = StartOutcome.VerifiedSuccess),
                )

            assertFalse("a submit after stop must be refused, not accepted", accepted)
            advanceUntilIdle()
            assertTrue("a refused command must not be processed", ops.handled.isEmpty())
        }
}
