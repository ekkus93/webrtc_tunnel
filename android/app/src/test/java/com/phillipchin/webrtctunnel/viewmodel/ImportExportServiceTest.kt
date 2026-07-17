package com.phillipchin.webrtctunnel.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
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
import java.io.IOException

/**
 * FIX6 P0-001-C: config import must consume the atomic-write result, must not report
 * success when the write fails, must propagate cancellation, and must redact secrets.
 *
 * Tests drive [ImportExportService] directly rather than through [ImportExportViewModel]:
 * the ViewModel's op runner still wraps the call in `runCatching` (a cancellation-swallow
 * fixed in Stage B / P0-005), so the service is the correct layer to prove cancellation
 * propagation now.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportServiceTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        File(app.filesDir, "config.toml").deleteRecursively()
    }

    private class WriteResultConfigRepository(
        context: android.content.Context,
        private val onWrite: () -> Result<Unit>,
    ) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> = onWrite()
    }

    private fun serviceWith(configRepository: ConfigRepository): ImportExportService {
        // No encrypted identity is stored, so import uses identity-less validation, and the
        // shared RecordingBridge's validateConfig returns valid by default.
        val deps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { RecordingBridge() },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository =
                    IdentityRepository(
                        app,
                        object : IdentityCrypto {
                            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                            override fun decrypt(payload: ByteArray): ByteArray = payload
                        },
                    ),
                dispatchers = inlineTestDispatchers(),
            )
        return ImportExportService(deps)
    }

    @Test
    fun configImportWriteFailureDoesNotReportImported() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full")) },
            )

        var thrown: Throwable? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: Exception) {
            thrown = error
        }

        assertTrue(
            "a failed config write must surface as a thrown failure, not a silent success",
            thrown is IOException,
        )
    }

    @Test
    fun configImportWriteFailureLeavesOldConfigUnchanged() {
        File(app.filesDir, "config.toml").writeText("format = \"old\"\n")
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { Result.failure(IOException("disk full")) },
            )

        runCatching { runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") } }

        assertEquals(
            "a failed import must leave the previous config on disk",
            "format = \"old\"\n",
            File(app.filesDir, "config.toml").readText(),
        )
    }

    @Test
    fun configImportCancellationPropagates() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) { throw CancellationException("cancelled during write") },
            )

        var caught: CancellationException? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue(
            "cancellation during import must propagate, not be converted into a normal failure",
            caught != null,
        )
    }

    @Test
    fun configImportWriteFailureRedactsSecretMessage() {
        val service =
            serviceWith(
                WriteResultConfigRepository(app) {
                    Result.failure(IOException("write failed: password=sentinel"))
                },
            )

        var message: String? = null
        try {
            runBlocking { service.importContent(ImportKind.Config, "format = \"imported\"\n") }
        } catch (error: IOException) {
            message = error.message
        }

        assertFalse("the raw secret must not reach the import failure", message.orEmpty().contains("sentinel"))
        assertTrue(message.orEmpty().contains("***REDACTED***"))
    }
}
