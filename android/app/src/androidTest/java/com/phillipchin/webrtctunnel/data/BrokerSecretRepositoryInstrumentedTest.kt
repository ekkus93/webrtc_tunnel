package com.phillipchin.webrtctunnel.data

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FIX9 P0-006: real Android permission-bit evidence for the managed broker secret.
 *
 * Robolectric unit tests inject BrokerSecretPermissionEnforcer fakes because its Os chmod/stat
 * shadows are not trusted as kernel-level evidence. This instrumentation test runs on a real
 * Android runtime/emulator and checks the actual low nine permission bits after persist and after
 * restore.
 */
@RunWith(AndroidJUnit4::class)
class BrokerSecretRepositoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    @Before
    fun setUp() {
        File(context.filesDir, "runtime").deleteRecursively()
    }

    @Test
    fun persistedAndRestoredBrokerSecretHasOwnerOnlyPermissions() {
        val repository = BrokerSecretRepository(context)

        repository.persist("original-secret").getOrThrow()
        assertArrayEquals("original-secret".toByteArray(), passwordFile.readBytes())
        assertEquals(OWNER_READ_WRITE_MODE, permissionBits(passwordFile))

        val snapshot = repository.captureSnapshot().getOrThrow()
        try {
            repository.persist("mutated-secret").getOrThrow()
            assertArrayEquals("mutated-secret".toByteArray(), passwordFile.readBytes())
            assertEquals(OWNER_READ_WRITE_MODE, permissionBits(passwordFile))

            repository.restore(snapshot).getOrThrow()
            assertArrayEquals("original-secret".toByteArray(), passwordFile.readBytes())
            assertEquals(OWNER_READ_WRITE_MODE, permissionBits(passwordFile))
        } finally {
            snapshot.wipe()
        }
    }

    private fun permissionBits(file: File): Int = Os.stat(file.absolutePath).st_mode and PERMISSION_BITS_MASK

    private companion object {
        // Octal 0600: owner read+write only.
        const val OWNER_READ_WRITE_MODE = 0x180
        const val PERMISSION_BITS_MASK = 0x1FF
    }
}
