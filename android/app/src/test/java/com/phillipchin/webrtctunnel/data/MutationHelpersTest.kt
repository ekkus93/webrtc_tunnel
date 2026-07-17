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
}
