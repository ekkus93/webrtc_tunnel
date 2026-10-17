package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class SetupPersistenceCoordinatorConcurrencyTest : SetupPersistenceCoordinatorTestBase() {
    // Routed through a parameter default so detekt's InjectDispatcher rule is satisfied while
    // still giving the concurrency test real multi-threaded parallelism.
    private fun parallelDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineDispatcher =
        dispatcher

    // FIX7 P2-001-A: replaces a Thread.sleep-widened race window with a deterministic barrier.
    // The first call is held open inside saveSetupInput (still holding the coordinator's real
    // Mutex) until this test explicitly releases it. The second call is launched with
    // CoroutineStart.UNDISPATCHED, which runs it synchronously, on this thread, up to its first
    // suspension point before `launch` returns — since the mutex is currently held, that first
    // suspension point is guaranteed to be the mutex acquisition itself. By the time the launch
    // call returns, the second call has therefore definitely attempted entry while the first is
    // still inside, with no elapsed-time guess anywhere.
    @Test
    fun twoSetupCoordinatorCallsCannotOverlap() =
        runBlocking {
            val probe = ConcurrencyProbe(context)
            val coordinator = coordinator(RecordingPreferences(), config = probe)

            val firstJob =
                launch(parallelDispatcher()) {
                    val result = coordinator.persist(request())
                    assertTrue("the first concurrent save must succeed", result is SetupPersistenceResult.Success)
                }
            probe.firstEntered.await()

            val secondJob =
                launch(parallelDispatcher(), start = CoroutineStart.UNDISPATCHED) {
                    val result = coordinator.persist(request())
                    assertTrue("the second concurrent save must succeed", result is SetupPersistenceResult.Success)
                }

            probe.releaseFirst.complete(Unit)
            joinAll(firstJob, secondJob)

            assertEquals("the coordinator mutex must serialize concurrent saves", 1, probe.maxConcurrent.get())
        }
}
