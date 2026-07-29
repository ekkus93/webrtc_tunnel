package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportExportCanonicalContractTest : AppViewModelTestBase() {
    @Test
    fun privateIdentityImportFailsWhenCanonicalPrivateMissing() =
        runBlocking {
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    canonicalPrivateIdentity = null,
                    canonicalPublicIdentity = "canonical-public",
                    peerId = "android-phone",
                )
            val error = importPrivateIdentityFailure()

            assertTrue(error.message.orEmpty().contains("canonical private identity"))
        }

    @Test
    fun privateIdentityImportFailsWhenCanonicalPublicMissing() =
        runBlocking {
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    canonicalPrivateIdentity = "canonical-private",
                    canonicalPublicIdentity = null,
                    peerId = "android-phone",
                )
            val error = importPrivateIdentityFailure()

            assertTrue(error.message.orEmpty().contains("canonical public identity"))
        }

    private suspend fun importPrivateIdentityFailure(): Exception {
        return try {
            ImportExportService(deps).importContent(ImportKind.PrivateIdentity, "source-private")
            error("private identity import unexpectedly succeeded")
        } catch (error: Exception) {
            error
        }
    }
}
