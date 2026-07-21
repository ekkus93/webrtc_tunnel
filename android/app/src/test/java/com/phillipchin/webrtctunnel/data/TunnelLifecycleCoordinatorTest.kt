package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        val processorFailedCalls = AtomicInteger(0)
        var startOfferThrows: Throwable? = null

        override fun onError(
            message: String,
            code: String,
            state: ServiceState,
        ) {
            errors.add(message to code)
        }

        override val onProcessorFailed: () -> Unit = {
            processorFailedCalls.incrementAndGet()
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
    fun handlerCancellationStopsProcessorAndRejectsLaterCommands() =
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

            // P1-007: the processor died from the propagated cancellation, so its finally closed
            // command acceptance — a later submit is now refused, not merely left unprocessed.
            val callsAfterCancellation = ops.startOfferCalls.get()
            val accepted = coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()
            assertFalse("a submit after the processor exits must be refused", accepted)
            assertEquals(
                "no later command may be processed once the processor has been killed by cancellation",
                callsAfterCancellation,
                ops.startOfferCalls.get(),
            )
            coordinator.stop()
        }

    @Test
    fun processorScopeCancellationRejectsLaterCommands() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val coordinator = TunnelLifecycleCoordinator(ops, scope)
            coordinator.start()
            advanceUntilIdle()

            scope.cancel("service destroyed")
            advanceUntilIdle()

            val accepted = coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()
            assertFalse("no command may be accepted after the processor scope is cancelled", accepted)
            assertTrue(ops.handled.isEmpty())
        }

    @Test
    fun recoverableExceptionPublishesAndContinues() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = IllegalStateException("recoverable password=secret")
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()
            assertEquals(1, ops.errors.size)
            assertEquals("lifecycle_command_failed", ops.errors.single().second)
            assertFalse("published message must be redacted", ops.errors.single().first.contains("secret"))

            // A later command is still processed: a recoverable exception must not kill the loop.
            ops.startOfferThrows = null
            coordinator.trySubmit(LifecycleCommand.Pause)
            advanceUntilIdle()
            assertTrue(ops.handled.contains("pause"))
            coordinator.stop()
        }

    @Test
    fun fatalErrorIsNotConvertedToLifecycleCommandFailed() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = OutOfMemoryError("fatal")
            val caught = AtomicReference<Throwable?>(null)
            val scope =
                CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> caught.set(error) },
                )
            val coordinator = TunnelLifecycleCoordinator(ops, scope)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertTrue("a fatal Error must not be normalized to lifecycle_command_failed", ops.errors.isEmpty())
            assertTrue("the fatal Error must propagate out of the processor", caught.get() is OutOfMemoryError)
            // The processor exited, so acceptance is closed.
            assertFalse(coordinator.trySubmit(LifecycleCommand.Pause))
        }

    @Test
    fun errorReporterFailureStopsProcessorAndRejectsLaterCommands() =
        runTest {
            val ops =
                object : CoordinatorOperations by RecordingCoordinatorOperations() {
                    override fun onError(
                        message: String,
                        code: String,
                        state: ServiceState,
                    ): Unit = error("reporter down")

                    override suspend fun startOffer() = error("trigger onError")
                }
            val caught = AtomicReference<Throwable?>(null)
            val scope =
                CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> caught.set(error) },
                )
            val coordinator = TunnelLifecycleCoordinator(ops, scope)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertTrue("a throwing reporter must stop the processor", caught.get() is IllegalStateException)
            assertFalse(
                "acceptance must be closed once the processor exits",
                coordinator.trySubmit(LifecycleCommand.Pause),
            )
        }

    @Test
    fun unexpectedCancellationNotifiesProcessorFailed() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = CancellationException("propagated cancellation")
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertEquals(
                "an unexpected (not requested via stop()) processor death must notify the owner",
                1,
                ops.processorFailedCalls.get(),
            )
        }

    @Test
    fun fatalErrorNotifiesProcessorFailed() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            ops.startOfferThrows = OutOfMemoryError("fatal")
            val scope =
                CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, _ -> },
                )
            val coordinator = TunnelLifecycleCoordinator(ops, scope)
            coordinator.start()

            coordinator.trySubmit(LifecycleCommand.StartOffer)
            advanceUntilIdle()

            assertEquals(
                "a fatal Throwable escaping a handler must notify the owner, not merely propagate",
                1,
                ops.processorFailedCalls.get(),
            )
        }

    @Test
    fun explicitStopDoesNotNotifyProcessorFailed() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.stop()

            assertEquals(
                "a requested stop() is expected teardown, not an unexpected processor death",
                0,
                ops.processorFailedCalls.get(),
            )
        }

    @Test
    fun stopIsIdempotent() =
        runTest {
            val ops = RecordingCoordinatorOperations()
            val coordinator = TunnelLifecycleCoordinator(ops, this)
            coordinator.start()

            coordinator.stop()
            coordinator.stop()

            assertFalse(coordinator.trySubmit(LifecycleCommand.StartOffer))
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
