package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

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

    private class CountingConfigRepository(
        context: android.content.Context,
    ) : ConfigRepository(context) {
        val ensureDefaultConfigCalls = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun ensureDefaultConfig(contents: String): Result<Unit> {
            ensureDefaultConfigCalls.incrementAndGet()
            return super.ensureDefaultConfig(contents)
        }
    }

    private fun coordinatorFor(
        repository: ConfigRepository,
        scope: CoroutineScope,
    ) = AppInitializationCoordinator(
        configRepository = repository,
        scope = scope,
        ioDispatcher = unconfinedDispatcher(),
    )

    // The only Dispatchers.Unconfined references live in these parameter defaults, per
    // the module's InjectDispatcher convention (see inlineTestDispatchers/realIoTestDispatchers).
    private fun unconfinedDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): CoroutineDispatcher =
        dispatcher

    private fun unconfinedScope(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): CoroutineScope =
        CoroutineScope(Job() + dispatcher)

    // FIX7 P1-003-C: start() must be idempotent — a repeated call (e.g. a second
    // Application.onCreate-equivalent race) must not launch a duplicate initialize().
    @Test
    fun initializationStartIsIdempotent() {
        val scope = unconfinedScope()
        val repository = CountingConfigRepository(context)
        val coordinator = coordinatorFor(repository, scope)

        val firstJob = coordinator.start()
        val secondJob = coordinator.start()

        assertEquals(
            "a repeated start() must return the same Job, not launch a new one",
            firstJob,
            secondJob,
        )
        assertEquals(
            "a repeated start() must not launch a duplicate initialize()",
            1,
            repository.ensureDefaultConfigCalls.get(),
        )
        assertEquals(AppInitializationState.Ready, coordinator.state.value)
        scope.cancel()
    }

    @Test
    fun readinessStartsAsInitializing() {
        val scope = unconfinedScope()
        val coordinator = coordinatorFor(ConfigRepository(context), scope)
        assertEquals(AppInitializationState.Initializing, coordinator.state.value)
        scope.cancel()
    }

    @Test
    fun successfulDefaultConfigCreationProducesReady() =
        runBlocking {
            val scope = unconfinedScope()
            val coordinator = coordinatorFor(ConfigRepository(context), scope)

            coordinator.initialize()

            assertEquals(AppInitializationState.Ready, coordinator.state.value)
            scope.cancel()
        }

    @Test
    fun defaultConfigFailureProducesFailedReadinessWithVisibleCode() =
        runBlocking {
            val scope = unconfinedScope()
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
            val scope = unconfinedScope()
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

    // FIX8 P1-002: real (non-Unconfined) IO-backed scope/dispatcher, needed so genuinely
    // concurrent start() calls from separate threads can actually race the old eager-launch
    // window this task closes — an Unconfined dispatcher never leaves that window open. The only
    // direct Dispatchers.IO references live in these parameter defaults (InjectDispatcher).
    private fun realIoScope(dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope =
        CoroutineScope(Job() + dispatcher)

    private fun coordinatorForRealIo(
        repository: ConfigRepository,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = AppInitializationCoordinator(configRepository = repository, scope = scope, ioDispatcher = dispatcher)

    /** Barrier at the first line of [AppInitializationCoordinator.initialize] (P1-002-B): counts
     * every entry into [ensureDefaultConfig], the only instruction `initialize` executes before
     * publishing its result. */
    private class BarrierConfigRepository(context: android.content.Context) : ConfigRepository(context) {
        val entries = AtomicInteger(0)

        override suspend fun ensureDefaultConfig(contents: String): Result<Unit> {
            entries.incrementAndGet()
            return super.ensureDefaultConfig(contents)
        }
    }

    /** Fires [threadCount] concurrent `coordinator.start()` calls, released simultaneously via a
     * latch (not sequentially), and returns every thread's resulting [Job] in call order. */
    private fun concurrentStarts(
        coordinator: AppInitializationCoordinator,
        threadCount: Int,
    ): List<Job> {
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val jobs = Collections.synchronizedList(mutableListOf<Job>())
        val threads =
            (1..threadCount).map {
                Thread {
                    ready.countDown()
                    go.await()
                    jobs.add(coordinator.start())
                }
            }
        threads.forEach { it.start() }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }
        return jobs.toList()
    }

    // concurrentInitializationStartRunsInitializeExactlyOnce
    @Test
    fun concurrentInitializationStartRunsInitializeExactlyOnce() {
        val scope = realIoScope()
        val repository = BarrierConfigRepository(context)
        val coordinator = coordinatorForRealIo(repository, scope)

        val jobs = concurrentStarts(coordinator, threadCount = 8)
        runBlocking { jobs.forEach { it.join() } }

        assertEquals(
            "eight concurrent start() calls must still run initialize()'s body exactly once",
            1,
            repository.entries.get(),
        )
        assertEquals(AppInitializationState.Ready, coordinator.state.value)
        scope.cancel()
    }

    // losingLazyInitializationJobExecutesNoInstruction
    @Test
    fun losingLazyInitializationJobExecutesNoInstruction() {
        val scope = realIoScope()
        val repository = BarrierConfigRepository(context)
        val coordinator = coordinatorForRealIo(repository, scope)

        val jobs = concurrentStarts(coordinator, threadCount = 8)
        runBlocking { jobs.forEach { it.join() } }

        // Every losing candidate was CoroutineStart.LAZY and cancelled before ever being
        // started, so — unlike the old eager-launch design — none of them can have executed any
        // part of initialize()'s body; only the single winner's entry is observed.
        assertEquals(
            "no losing candidate may execute any instruction of initialize()",
            1,
            repository.entries.get(),
        )
        assertTrue(
            "every returned job must be the same winner, and it must complete successfully",
            jobs.all { it === jobs.first() && it.isCompleted && !it.isCancelled },
        )
        scope.cancel()
    }

    // allConcurrentCallersReceiveSameWinnerJob
    @Test
    fun allConcurrentCallersReceiveSameWinnerJob() {
        val scope = realIoScope()
        val repository = BarrierConfigRepository(context)
        val coordinator = coordinatorForRealIo(repository, scope)

        val jobs = concurrentStarts(coordinator, threadCount = 8)
        runBlocking { jobs.forEach { it.join() } }

        assertEquals(8, jobs.size)
        assertTrue(
            "every concurrent caller must receive the identical winner Job instance",
            jobs.all { it === jobs.first() },
        )
        scope.cancel()
    }

    // initializationFailureStillPublishesOneFailedState
    @Test
    fun initializationFailureStillPublishesOneFailedState() {
        val scope = realIoScope()
        val repository =
            object : ConfigRepository(context) {
                val calls = AtomicInteger(0)

                override suspend fun ensureDefaultConfig(contents: String): Result<Unit> {
                    calls.incrementAndGet()
                    return Result.failure(IOException("disk full"))
                }
            }
        val coordinator = coordinatorForRealIo(repository, scope)

        val jobs = concurrentStarts(coordinator, threadCount = 8)
        runBlocking { jobs.forEach { it.join() } }

        assertEquals(
            "a failing initialize() must still run exactly once under concurrent start()",
            1,
            repository.calls.get(),
        )
        val state = coordinator.state.value
        assertTrue("initialization failure must still be visible, not silent", state is AppInitializationState.Failed)
        assertEquals("config_initialization_failed", (state as AppInitializationState.Failed).code)
        scope.cancel()
    }
}
