package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * FIX6 Stage A-1: prerequisite helpers for the candidate-file and cancellation rules
 * (INV-003 / INV-012 / INV-013).
 */
@RunWith(RobolectricTestRunner::class)
class MutationHelpersTest {
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "candidates")
        cacheDir.deleteRecursively()
    }

    @Test
    fun mutationResultReturnsSuccessValue() =
        runBlocking {
            val result = mutationResult { "committed" }
            assertEquals("committed", result.getOrNull())
        }

    @Test
    fun mutationResultConvertsRecoverableExceptionToFailure() =
        runBlocking {
            val result = mutationResult<Unit> { throw IOException("disk full") }
            assertTrue(result.isFailure)
            assertEquals("disk full", result.exceptionOrNull()?.message)
        }

    @Test
    fun mutationResultRethrowsCancellationInsteadOfConvertingItToFailure() {
        // The whole point of this helper over runCatching: a cancelled mutation must
        // terminate, not come back as an ordinary failure that drives rollback or a
        // user-visible error message.
        var caught: CancellationException? = null
        try {
            runBlocking {
                mutationResult<Unit> { throw CancellationException("cancelled mid-write") }
            }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertTrue("CancellationException must propagate out of mutationResult", caught != null)
    }

    @Test
    fun createCandidateFileProducesUniquePathsForTheSamePrefix() {
        val first = createCandidateFile(cacheDir, "setup-config-")
        val second = createCandidateFile(cacheDir, "setup-config-")

        assertNotEquals(
            "two concurrent operations must not share one candidate path",
            first.absolutePath,
            second.absolutePath,
        )
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun createCandidateFileCreatesCacheDirWhenAbsent() {
        assertFalse(cacheDir.exists())
        val candidate = createCandidateFile(cacheDir, "import-config-")
        assertTrue(candidate.exists())
    }

    @Test
    fun deleteCandidateFileSafelyRemovesTheFileAndReportsSuccess() {
        val candidate = createCandidateFile(cacheDir, "forwards-config-")
        val result = deleteCandidateFileSafely(candidate)

        assertTrue(result.isSuccess)
        assertFalse(candidate.exists())
    }

    @Test
    fun deleteCandidateFileSafelyIsSuccessWhenFileIsAlreadyGone() {
        val candidate = createCandidateFile(cacheDir, "forwards-config-")
        candidate.delete()

        assertTrue(
            "deleting an already-absent candidate is not a failure",
            deleteCandidateFileSafely(candidate).isSuccess,
        )
    }

    @Test
    fun deleteCandidateFileSafelyReturnsFailureInsteadOfThrowing() {
        // A directory with a child cannot be removed by deleteIfExists, which is a
        // portable way to force a real IOException without permission tricks.
        val undeletable = File(cacheDir, "occupied-dir").apply { mkdirs() }
        File(undeletable, "child.txt").writeText("blocks deletion")

        val result = deleteCandidateFileSafely(undeletable)

        assertTrue("cleanup failure must be returned, not thrown", result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    // FIX7 P0-002-C: withCandidateFile/withTemporaryDirectory cleanup composition (INV-010).

    @Test
    fun candidatePrimaryFailurePreservedAndCleanupSuppressed() =
        runBlocking {
            val error =
                try {
                    withCandidateFile(cacheDir, "setup-config-") { candidate ->
                        // Make cleanup itself fail: replace the candidate file with a
                        // non-empty directory of the same name before the block returns.
                        candidate.delete()
                        candidate.mkdirs()
                        File(candidate, "child.txt").writeText("blocks deletion")
                        error("primary validation failure")
                    }
                } catch (thrown: IllegalStateException) {
                    thrown
                }

            assertEquals("primary validation failure", error.message)
            assertTrue(
                "cleanup failure must be attached as suppressed, not lost",
                error.suppressed.isNotEmpty(),
            )
        }

    @Test
    fun candidateCancellationPreservedAndCleanupSuppressed() {
        var caught: CancellationException? = null
        try {
            runBlocking {
                withCandidateFile(cacheDir, "setup-config-") { candidate ->
                    candidate.delete()
                    candidate.mkdirs()
                    File(candidate, "child.txt").writeText("blocks deletion")
                    throw CancellationException("cancelled mid-validation")
                }
            }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue(
            "cleanup failure during cancellation must be attached as suppressed",
            caught?.suppressed?.isNotEmpty() == true,
        )
    }

    @Test
    fun candidateSuccessfulBlockBecomesFailureWhenCleanupFails() =
        runBlocking {
            val error =
                try {
                    withCandidateFile(cacheDir, "setup-config-") { candidate ->
                        candidate.delete()
                        candidate.mkdirs()
                        File(candidate, "child.txt").writeText("blocks deletion")
                        "would-be success value"
                    }
                    null
                } catch (thrown: CandidateCleanupException) {
                    thrown
                }

            assertTrue(
                "a successful block whose cleanup fails must fail overall, not report false success",
                error != null,
            )
        }

    @Test
    fun candidateSuccessfulBlockReturnsValueWhenCleanupSucceeds() =
        runBlocking {
            val value = withCandidateFile(cacheDir, "setup-config-") { candidate -> "committed:${candidate.name}" }
            assertTrue(value.startsWith("committed:"))
        }

    @Test
    fun temporaryDirectoryCleanupFailureUsesSameCompositionRules() =
        runBlocking {
            // A real recursive delete empties a directory bottom-up before removing it, so
            // there is no portable non-permission way to make it fail — inject a failing fake
            // instead (the same technique AtomicConfigFileOps tests use).
            val failingDelete: (File) -> Result<Unit> = {
                Result.failure(IOException("simulated workspace cleanup failure"))
            }

            val error =
                try {
                    withTemporaryDirectory(cacheDir, "setup-validation-", deleteRecursively = failingDelete) {
                        error("primary workspace failure")
                    }
                } catch (thrown: IllegalStateException) {
                    thrown
                }

            assertEquals("primary workspace failure", error.message)
            assertTrue(
                "cleanup failure must be attached as suppressed, not lost",
                error.suppressed.isNotEmpty(),
            )
        }

    @Test
    fun temporaryDirectorySuccessfulBlockBecomesFailureWhenCleanupFails() =
        runBlocking {
            val failingDelete: (File) -> Result<Unit> = {
                Result.failure(IOException("simulated workspace cleanup failure"))
            }

            val error =
                try {
                    withTemporaryDirectory(cacheDir, "setup-validation-", deleteRecursively = failingDelete) {
                        "would-be success value"
                    }
                    null
                } catch (thrown: CandidateCleanupException) {
                    thrown
                }

            assertTrue(
                "a successful block whose workspace cleanup fails must fail overall",
                error != null,
            )
        }

    @Test
    fun temporaryDirectoryReturnsValueWhenCleanupSucceeds() =
        runBlocking {
            val value = withTemporaryDirectory(cacheDir, "setup-validation-") { workspace -> workspace.exists() }
            assertTrue(value)
        }
}
