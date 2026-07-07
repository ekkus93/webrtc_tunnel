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
        // Real dispatchers; suspend repository methods complete under runBlocking.
        repo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
    }

    private fun forward(
        id: String,
        port: Int,
    ) = ForwardConfig(id = id, name = id, localPort = port, remoteForwardId = id, enabled = true)

    @Test
    fun upsertUpdatesObservableState() =
        runBlocking {
            val result = repo.upsert(forward("x", 1234))
            assertTrue(result.validationResult.valid)
            assertTrue(repo.forwards.value.any { it.id == "x" })
        }

    @Test
    fun deleteUpdatesObservableState() =
        runBlocking {
            repo.save(listOf(forward("d", 3333)))
            assertTrue(repo.forwards.value.any { it.id == "d" })
            repo.delete("d")
            assertTrue(repo.forwards.value.none { it.id == "d" })
        }

    @Test
    fun mutationBlockedWhenStartupBaselineIsCorrupt() =
        runBlocking {
            file.writeText("{ corrupt json")
            val corruptRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())

            val result = corruptRepo.upsert(forward("x", 1234))

            assertFalse(result.validationResult.valid)
            // The corrupt file must not be overwritten with a fresh/empty baseline.
            assertTrue(file.readText().contains("corrupt"))
        }

    @Test
    fun deleteBlockedWhenStartupBaselineIsCorrupt() =
        runBlocking {
            file.writeText("{ corrupt json")
            val corruptRepo = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())

            val result = corruptRepo.delete("anything")

            assertFalse(result.validationResult.valid)
            // A corrupt forwards file must never be overwritten by a delete that dropped
            // the (unparseable) entries — the user's file is preserved for repair.
            assertTrue(file.readText().contains("corrupt"))
        }

    @Test
    fun upsertAfterDiskCorruptionPreservesInMemoryList() =
        runBlocking {
            repo.save(listOf(forward("keep", 1111)))
            file.writeText("{ corrupt json")

            val result = repo.upsert(forward("added", 2222))

            assertTrue(result.validationResult.valid)
            assertTrue(repo.forwards.value.any { it.id == "keep" })
            assertTrue(repo.forwards.value.any { it.id == "added" })
        }

    @Test
    fun validationFailureLeavesObservableStateUnchanged() =
        runBlocking {
            repo.save(listOf(forward("a", 1111)))
            val before = repo.forwards.value

            val result = repo.upsert(forward("b", 1111)) // duplicate port

            assertFalse(result.validationResult.valid)
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
            repo.save(listOf(forward("keep", 2222)))
            assertTrue(repo.forwards.value.any { it.id == "keep" })

            file.writeText("{ corrupt json")
            repo.refresh()

            // The corrupt file must not erase the in-memory list.
            assertTrue(repo.forwards.value.any { it.id == "keep" })
        }
}
