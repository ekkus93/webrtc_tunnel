package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * FIX8 P0-003-C/F: the shared same-directory temp-plus-atomic-move primitive
 * ([atomicReplaceBytesWith]) must leave the destination's prior exact bytes untouched when the
 * temp write fails before any move, and must return a failure (not throw) for every ordinary
 * exception — including [SecurityException], not just [IOException].
 */
@RunWith(RobolectricTestRunner::class)
class AtomicReplaceBytesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var destination: File

    @Before
    fun setUp() {
        destination = File(context.filesDir, "atomic-replace-target.bin")
        destination.delete()
    }

    private class FakeOps(
        private val writeThrows: Throwable? = null,
    ) : AtomicConfigFileOps by RealAtomicConfigFileOps {
        override fun writeBytes(
            temp: Path,
            bytes: ByteArray,
        ) {
            writeThrows?.let { throw it }
            RealAtomicConfigFileOps.writeBytes(temp, bytes)
        }
    }

    @Test
    fun setupInputAtomicWriteFailureBeforeMoveLeavesPriorBytesExact() {
        val priorBytes = byteArrayOf(1, 2, 3, 4, 5)
        destination.writeBytes(priorBytes)

        val result =
            atomicReplaceBytesWith(
                destination,
                byteArrayOf(9, 9, 9),
                FakeOps(writeThrows = IOException("temp write boom")),
            )

        assertTrue("a temp-write failure before any move must surface as a failure", result.isFailure)
        assertArrayEquals(
            "the destination must never be touched when the write fails before the move",
            priorBytes,
            destination.readBytes(),
        )
    }

    @Test
    fun securityExceptionFromWriteReturnsFailureNotThrow() {
        val priorBytes = byteArrayOf(7, 7, 7)
        destination.writeBytes(priorBytes)

        val result =
            atomicReplaceBytesWith(
                destination,
                byteArrayOf(1, 2, 3),
                FakeOps(writeThrows = SecurityException("denied")),
            )

        assertTrue(
            "a SecurityException must be caught and returned as a failure, not thrown",
            result.isFailure,
        )
        assertEquals(SecurityException::class.java, result.exceptionOrNull()?.javaClass)
        assertArrayEquals(priorBytes, destination.readBytes())
    }

    @Test
    fun successfulReplaceMovesExactBytesAndCleansUpTemp() {
        destination.writeBytes(byteArrayOf(0))
        val parent = destination.parentFile!!
        val before = parent.listFiles()?.map { it.name }?.toSet().orEmpty()

        val result = atomicReplaceBytesWith(destination, byteArrayOf(5, 6, 7), RealAtomicConfigFileOps)

        assertTrue(result.isSuccess)
        assertArrayEquals(byteArrayOf(5, 6, 7), destination.readBytes())
        val after = parent.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("the temp file must be cleaned up after a successful move", before, after)
    }
}
