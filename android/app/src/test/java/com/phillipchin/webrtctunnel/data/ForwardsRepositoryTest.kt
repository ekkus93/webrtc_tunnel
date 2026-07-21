package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ForwardsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.filesDir, "forwards.json")
    private lateinit var repo: ForwardsRepository

    @Before
    fun setUp() {
        file.delete()
        file.writeText("[]") // Write a valid empty array so the initial load succeeds.
        // Real dispatchers; suspend repository methods complete under runBlocking.
        repo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
        // FIX7 P1-003-B: construction no longer reads the file — refresh() once to reach
        // Ready before any test mutates, matching how every real caller (HomeViewModel,
        // ForwardsViewModel) refreshes in its own init block before mutating.
        runBlocking { repo.refresh() }
    }

    private fun forward(
        id: String,
        port: Int,
    ) = ForwardConfig(
        id = id,
        name = id,
        localPort = port,
        remoteForwardId = id,
        enabled = true,
    )

    @Test
    fun upsertWithReceiptAddsForwardAndReturnsReceipt() =
        runBlocking {
            val forward = forward("x", 1234)
            val before = repo.current()
            val result = repo.upsertWithReceipt(forward)

            assertTrue(result.isSuccess)
            val receipt = result.getOrThrow()
            assertEquals(before, receipt.before)
            assertTrue(receipt.after.any { it.id == "x" })
            // FIX7 P1-003-B: setUp()'s refresh() (reaching Ready) already advances revision
            // to 1, so the first mutation commits revision 2, not 1.
            assertEquals(2, receipt.committedRevision)
        }

    @Test
    fun deleteWithReceiptRemovesForwardAndReturnsReceipt() =
        runBlocking {
            val forward = forward("d", 3333)
            repo.upsertWithReceipt(forward).getOrThrow()
            val before = repo.current()

            val result = repo.deleteWithReceipt("d")

            assertTrue(result.isSuccess)
            val receipt = result.getOrThrow()
            assertTrue("delete receipt should reflect the forward in before", receipt.before.any { it.id == "d" })
            assertTrue("delete receipt should not have forward in after", receipt.after.none { it.id == "d" })
        }

    @Test
    fun mutationBlockedWhenStartupBaselineIsCorrupt() =
        runBlocking {
            file.writeText("{ corrupt json")
            val corruptRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            corruptRepo.refresh()

            val result = corruptRepo.upsertWithReceipt(forward("x", 1234))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() is ForwardsMutationBlocked)
            // The corrupt file must not be overwritten with a fresh/empty baseline.
            assertTrue(file.readText().contains("corrupt"))
        }

    @Test
    fun mutationBlockedBeforeFirstRefreshCompletes() =
        runBlocking {
            // Even a valid on-disk baseline must not be mutated before it has actually been
            // read: construction no longer reads the file (FIX7 P1-003-B), so a mutation
            // arriving before the first refresh() must be blocked, not silently accepted
            // against the placeholder empty in-memory list.
            val freshRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())

            val result = freshRepo.upsertWithReceipt(forward("x", 1234))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() is ForwardsMutationBlocked)
        }

    @Test
    fun deleteBlockedWhenStartupBaselineIsCorrupt() =
        runBlocking {
            file.writeText("{ corrupt json")
            val corruptRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            corruptRepo.refresh()

            val result = corruptRepo.deleteWithReceipt("anything")

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() is ForwardsMutationBlocked)
            // A corrupt forwards file must never be overwritten by a delete that dropped
            // the (unparseable) entries — the user's file is preserved for repair.
            assertTrue(file.readText().contains("corrupt"))
        }

    @Test
    fun upsertAfterDiskCorruptionPreservesInMemoryList() =
        runBlocking {
            // Ensure a valid initial baseline by writing a valid empty array first.
            file.writeText("[]")
            val validRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            validRepo.refresh()

            validRepo.upsertWithReceipt(forward("keep", 1111)).getOrThrow()
            file.writeText("{ corrupt json")

            val result = validRepo.upsertWithReceipt(forward("added", 2222))

            assertTrue(result.isSuccess)
            assertTrue(validRepo.forwards.value.any { it.id == "keep" })
            assertTrue(validRepo.forwards.value.any { it.id == "added" })
        }

    @Test
    fun validationFailureLeavesObservableStateUnchanged() =
        runBlocking {
            repo.upsertWithReceipt(forward("a", 1111)).getOrThrow()
            val before = repo.forwards.value

            val result = repo.upsertWithReceipt(forward("b", 1111)) // duplicate port

            assertFalse(result.isSuccess)
            assertEquals(before, repo.forwards.value)
        }

    @Test
    fun loadErrorDistinguishesParseFailureFromReadFailure() =
        runBlocking {
            file.writeText("{ corrupt json")
            val parseFailureRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            parseFailureRepo.refresh()
            val parseMessage = parseFailureRepo.loadError.value

            file.delete()
            file.writeText("[]")
            assertTrue(file.setReadable(false))
            try {
                val readFailureRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
                readFailureRepo.refresh()
                val readMessage = readFailureRepo.loadError.value

                // Both must be reported, but with distinct wording — a caller must not have
                // to guess "corrupt" for a permission problem, or vice versa (P1-003).
                assertTrue(parseMessage != null && readMessage != null)
                assertFalse(parseMessage == readMessage)
                assertTrue(parseMessage?.contains("corrupt") == true)
                assertFalse(readMessage?.contains("corrupt") == true)
            } finally {
                file.setReadable(true)
            }
        }

    @Test
    fun refreshKeepsPriorListWhenFileIsCorrupt() =
        runBlocking {
            // Seed a valid in-memory state via the receipt API.
            repo.upsertWithReceipt(forward("keep", 2222)).getOrThrow()
            assertTrue(repo.forwards.value.any { it.id == "keep" })

            file.writeText("{ corrupt json")
            repo.refresh()

            // The corrupt file must not erase the in-memory list.
            assertTrue(repo.forwards.value.any { it.id == "keep" })
        }

    @Test
    fun rollbackRestoresExactState() =
        runBlocking {
            val forward = forward("x", 1234)
            val receipt = repo.upsertWithReceipt(forward).getOrThrow()

            val rollbackResult = repo.rollbackReceipt(receipt)

            assertTrue(rollbackResult.isSuccess)
            assertTrue(repo.current().none { it.id == "x" })
        }

    @Test
    fun rollbackFailsWithStaleReceipt() =
        runBlocking {
            val forward = forward("x", 1234)
            val receipt = repo.upsertWithReceipt(forward).getOrThrow()

            // Another mutation advances the revision.
            repo.upsertWithReceipt(forward("y", 5555)).getOrThrow()

            val rollbackResult = repo.rollbackReceipt(receipt)

            assertFalse(rollbackResult.isSuccess)
            assertTrue(rollbackResult.exceptionOrNull() is ForwardsRevisionMismatchException)
        }

    @Test
    fun refreshInvalidatesOldReceipts() =
        runBlocking {
            val forward = forward("x", 1234)
            val receipt = repo.upsertWithReceipt(forward).getOrThrow()

            // Refresh with valid data advances the revision.
            file.writeText(
                "[{\"id\":\"y\",\"name\":\"y\",\"localPort\":9999,\"remoteForwardId\":\"y\",\"enabled\":true}]",
            )
            repo.refresh()

            val rollbackResult = repo.rollbackReceipt(receipt)

            assertFalse(rollbackResult.isSuccess)
        }

    @Test
    fun resetForwardsClearsStateAndLoadError() =
        runBlocking {
            repo.upsertWithReceipt(forward("x", 1234)).getOrThrow()

            val result = repo.resetForwards()

            assertTrue(result.isSuccess)
            assertTrue(repo.current().isEmpty())
            assertTrue(repo.loadError.value == null)
        }

    // FIX6 P0-005-B: mutations must rethrow CancellationException, not convert it into a
    // Result.failure. A store whose saveForwards throws CancellationException simulates
    // cancellation landing inside the mutation block: the old runCatching would have
    // swallowed it into a failure value, mutationResult rethrows it.

    private class CancellingSaveStore(
        private val initial: List<ForwardConfig> = emptyList(),
    ) : ForwardsStore {
        override fun loadForwardsResult(): Result<List<ForwardConfig>> = Result.success(initial)

        override fun saveForwards(forwards: List<ForwardConfig>): Unit =
            throw CancellationException("cancelled during save")

        override fun validateForwards(forwards: List<ForwardConfig>): String? = null
    }

    private fun cancellingRepo(initial: List<ForwardConfig> = emptyList()): ForwardsRepository =
        ForwardsRepository(CancellingSaveStore(initial), AppDispatchers()).also {
            // FIX7 P1-003-B: construction no longer reads a baseline — refresh() (a plain
            // success against CancellingSaveStore.loadForwardsResult()) to reach Ready so
            // these tests exercise cancellation during the mutation itself, not the
            // now-separate not-yet-Ready block.
            runBlocking { it.refresh() }
        }

    private inline fun assertCancellationPropagates(block: () -> Unit) {
        var caught: CancellationException? = null
        try {
            block()
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue("CancellationException must propagate, not be converted to a failure", caught != null)
    }

    @Test
    fun upsertCancellationPropagatesAndDoesNotPublish() {
        val cancelling = cancellingRepo()
        assertCancellationPropagates {
            runBlocking { cancelling.upsertWithReceipt(forward("web", 9090)).getOrThrow() }
        }
        assertTrue("a cancelled upsert must not publish", cancelling.current().isEmpty())
    }

    @Test
    fun deleteCancellationPropagatesAndDoesNotPublish() {
        val cancelling = cancellingRepo(listOf(forward("web", 9090)))
        assertCancellationPropagates {
            runBlocking { cancelling.deleteWithReceipt("web").getOrThrow() }
        }
        assertTrue(
            "a cancelled delete must not publish the removal",
            cancelling.current().any { it.id == "web" },
        )
    }

    @Test
    fun rollbackCancellationPropagatesAndDoesNotPublish() {
        val cancelling = cancellingRepo(listOf(forward("web", 9090)))
        // FIX7 P1-003-B: cancellingRepo() now calls refresh() (reaching Ready) to advance
        // the repo's revision to 1, so committedRevision must match that, not the pre-refresh
        // initial 0, to pass the revision guard and reach the cancelling save.
        val receipt = ForwardsMutationReceipt(before = emptyList(), after = emptyList(), committedRevision = 1)
        assertCancellationPropagates {
            runBlocking { cancelling.rollbackReceipt(receipt).getOrThrow() }
        }
        assertTrue(cancelling.current().any { it.id == "web" })
    }

    @Test
    fun resetCancellationPropagatesAndDoesNotPublish() {
        val cancelling = cancellingRepo(listOf(forward("web", 9090)))
        assertCancellationPropagates {
            runBlocking { cancelling.resetForwards().getOrThrow() }
        }
        assertTrue("a cancelled reset must not publish the empty list", cancelling.current().any { it.id == "web" })
    }

    @Test
    fun transactionalRestoreCancellationPropagatesAndDoesNotPublish() {
        val cancelling = cancellingRepo(listOf(forward("web", 9090)))
        assertCancellationPropagates {
            runBlocking { cancelling.restoreForTransactionalReset(listOf(forward("api", 9091))).getOrThrow() }
        }
        assertFalse("a cancelled restore must not publish", cancelling.current().any { it.id == "api" })
    }
}
