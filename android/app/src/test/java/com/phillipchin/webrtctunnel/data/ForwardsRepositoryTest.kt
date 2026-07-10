package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
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
            assertEquals(1, receipt.committedRevision)
        }

    @Test
    fun deleteWithReceiptRemovesForwardAndReturnsReceipt() =
        runBlocking {
            val forward = forward("d", 3333)
            repo.upsertWithReceipt(forward)
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

            val result = corruptRepo.upsertWithReceipt(forward("x", 1234))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() is ForwardsMutationBlocked)
            // The corrupt file must not be overwritten with a fresh/empty baseline.
            assertTrue(file.readText().contains("corrupt"))
        }

    @Test
    fun deleteBlockedWhenStartupBaselineIsCorrupt() =
        runBlocking {
            file.writeText("{ corrupt json")
            val corruptRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())

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

            validRepo.upsertWithReceipt(forward("keep", 1111))
            file.writeText("{ corrupt json")

            val result = validRepo.upsertWithReceipt(forward("added", 2222))

            assertTrue(result.isSuccess)
            assertTrue(validRepo.forwards.value.any { it.id == "keep" })
            assertTrue(validRepo.forwards.value.any { it.id == "added" })
        }

    @Test
    fun validationFailureLeavesObservableStateUnchanged() =
        runBlocking {
            repo.upsertWithReceipt(forward("a", 1111))
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
            val parseMessage = parseFailureRepo.loadError.value

            file.delete()
            file.writeText("[]")
            assertTrue(file.setReadable(false))
            try {
                val readFailureRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
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
            // Seed a valid in-memory state via save() (bypasses loadError guard).
            repo.save(listOf(forward("keep", 2222)))
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
            repo.upsertWithReceipt(forward("y", 5555))

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
            repo.upsertWithReceipt(forward("x", 1234))

            val result = repo.resetForwards()

            assertTrue(result.isSuccess)
            assertTrue(repo.current().isEmpty())
            assertTrue(repo.loadError.value == null)
        }

    @Test
    fun savePersistsExactList() =
        runBlocking {
            val forwards = listOf(forward("a", 1111), forward("b", 2222))
            val result = repo.save(forwards)

            assertTrue(result.isSuccess)
            assertEquals(forwards, repo.current())
        }
}
