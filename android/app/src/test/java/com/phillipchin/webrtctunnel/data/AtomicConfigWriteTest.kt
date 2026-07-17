package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * FIX6 P1-006: the atomic config write keeps temp cleanup inside the returned Result — a cleanup
 * failure never overwrites a primary failure, a cleanup failure after a successful move surfaces
 * as a failure, cancellation is rethrown with cleanup suppressed, and the move fallback still
 * replaces the destination.
 */
@RunWith(RobolectricTestRunner::class)
class AtomicConfigWriteTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var configFile: File

    @Before
    fun setUp() {
        configFile = File(context.filesDir, "config.toml")
        configFile.delete()
    }

    private class FakeOps(
        var writeThrows: Throwable? = null,
        var atomicMoveThrows: Throwable? = null,
        var deleteThrows: IOException? = null,
    ) : AtomicConfigFileOps {
        var plainMoveCalled = false

        override fun createTempFile(
            dir: Path,
            prefix: String,
            suffix: String,
        ): Path = Files.createTempFile(dir, prefix, suffix)

        override fun writeText(
            temp: Path,
            contents: String,
        ) {
            writeThrows?.let { throw it }
            temp.toFile().writeText(contents)
        }

        override fun atomicMove(
            temp: Path,
            destination: Path,
        ) {
            atomicMoveThrows?.let { throw it }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun plainMove(
            temp: Path,
            destination: Path,
        ) {
            plainMoveCalled = true
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun deleteIfExists(temp: Path) {
            deleteThrows?.let { throw it }
            Files.deleteIfExists(temp)
        }
    }

    @Test
    fun cleanupFailureAfterPrimaryFailurePreservesPrimaryAndSuppressesCleanup() {
        val ops =
            FakeOps(
                writeThrows = IOException("primary write boom"),
                deleteThrows = IOException("cleanup boom"),
            )

        val result = writeConfigAtomicallyWith(configFile, "data", ops)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()!!
        assertEquals("the primary failure must be preserved", "primary write boom", error.message)
        assertTrue(
            "the cleanup failure must be attached as suppressed",
            error.suppressed.any { it.message == "cleanup boom" },
        )
    }

    @Test
    fun cleanupFailureAfterSuccessfulMoveReturnsFailure() {
        val ops = FakeOps(deleteThrows = IOException("cleanup boom"))

        val result = writeConfigAtomicallyWith(configFile, "data", ops)

        assertTrue("a cleanup failure after a successful move must surface", result.isFailure)
        assertEquals("cleanup boom", result.exceptionOrNull()?.message)
        // The successful move still applied.
        assertEquals("data", configFile.readText())
    }

    @Test
    fun cancellationPreservesCancellationAndSuppressesCleanupFailure() {
        val ops =
            FakeOps(
                writeThrows = CancellationException("cancelled mid-write"),
                deleteThrows = IOException("cleanup boom"),
            )

        var caught: CancellationException? = null
        try {
            writeConfigAtomicallyWith(configFile, "data", ops)
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue("cancellation must propagate", caught != null)
        assertTrue(
            "the cleanup failure must be suppressed on the cancellation",
            caught!!.suppressed.any { it.message == "cleanup boom" },
        )
    }

    @Test
    fun atomicMoveFallbackStillReplacesDestination() {
        configFile.writeText("stale")
        val ops = FakeOps(atomicMoveThrows = AtomicMoveNotSupportedException("t", "d", "unsupported"))

        val result = writeConfigAtomicallyWith(configFile, "fresh", ops)

        assertTrue(result.isSuccess)
        assertTrue("the plain-move fallback must run", ops.plainMoveCalled)
        assertEquals("fresh", configFile.readText())
    }
}
