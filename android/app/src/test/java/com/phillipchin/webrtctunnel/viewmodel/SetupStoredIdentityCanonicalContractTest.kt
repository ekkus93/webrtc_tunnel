package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Post-FIX9 stored-identity compatibility contract.
 *
 * Existing installations may have an encrypted private identity paired with the public-identity
 * file produced by an older app/native version. When the native validator returns a canonical
 * public identity, that derived value is authoritative for the setup save UI. When it does not,
 * the coherently-read stored public file remains a compatibility fallback only: it cannot replace
 * the peer ID derived from the decrypted private identity and cannot bypass the local-peer match
 * check. Existing identity storage is not rewritten by a setup save unless a draft replacement is
 * explicitly imported/generated.
 */
@RunWith(RobolectricTestRunner::class)
class SetupStoredIdentityCanonicalContractTest : AppViewModelTestBase() {
    @Test
    fun canonicalPublicDerivedFromPrivateIdentityOverridesStoredPublicForSaveResult() =
        runBlocking {
            prepareStoredIdentity("legacy-stored-public")
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    message = null,
                    peerId = LOCAL_PEER,
                    canonicalPublicIdentity = "canonical-public-from-private",
                    canonicalPrivateIdentity = "stored-private",
                )

            val viewModel = readyWizard(localPeerId = LOCAL_PEER)
            viewModel.save.saveAndApplyConfig()
            awaitCondition(description = "stored identity save completed") {
                !viewModel.state.value.isBusy && viewModel.state.value.saveResult != null
            }

            assertEquals("Configuration saved", viewModel.state.value.saveResult)
            assertNull(viewModel.state.value.errorMessage)
            assertEquals("canonical-public-from-private", viewModel.state.value.localPublicIdentity)
            assertEquals(LOCAL_PEER, viewModel.state.value.identityPeerId)
            // A save using an existing identity is validation-only for the identity stage. It must
            // not silently rewrite the pair merely because a canonical public rendering exists.
            assertEquals("legacy-stored-public", File(app.filesDir, "identity.pub").readText())
            assertTrue(File(app.filesDir, "config.toml").exists())
        }

    @Test
    fun storedPublicFallbackCannotOverridePeerIdDerivedFromPrivateIdentity() =
        runBlocking {
            prepareStoredIdentity("public peer_id=attacker-peer")
            recordingBridge.privateIdentityValidationResult =
                IdentityValidationResult(
                    valid = true,
                    message = null,
                    peerId = LOCAL_PEER,
                    canonicalPublicIdentity = null,
                    canonicalPrivateIdentity = "stored-private",
                )

            val viewModel = readyWizard(localPeerId = "attacker-peer")
            viewModel.save.saveAndApplyConfig()
            awaitCondition(description = "stored identity mismatch rejected") {
                !viewModel.state.value.isBusy && viewModel.state.value.errorMessage != null
            }

            assertNull(viewModel.state.value.saveResult)
            val failure = viewModel.state.value.errorMessage
            assertNotNull(failure)
            assertTrue(failure!!.startsWith("Local peer ID must match private identity peer ID"))
            assertFalse(
                "stored public identity must not authorize a save for a different private peer",
                File(app.filesDir, "config.toml").exists(),
            )
            assertEquals("public peer_id=attacker-peer", File(app.filesDir, "identity.pub").readText())
        }

    private fun prepareStoredIdentity(publicIdentity: String) {
        clearRelevantStorage()
        deps.identityRepository.storeEncryptedIdentity("stored-private".encodeToByteArray(), publicIdentity)
        deps.forwardsStore.saveForwards(
            listOf(
                ForwardConfig(
                    id = "svc",
                    name = "svc",
                    localPort = 8080,
                    remoteForwardId = "svc",
                    enabled = true,
                ),
            ),
        )
    }

    private suspend fun readyWizard(localPeerId: String): SetupViewModel {
        deps.forwardsRepository.refresh()
        val viewModel = SetupViewModel(deps)
        awaitCondition(description = "setup baseline ready") {
            viewModel.loadState.value is SetupLoadState.Ready
        }
        recordingBridge.publicIdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                message = null,
                peerId = REMOTE_PEER,
                canonicalPublicIdentity = "remote-public",
                canonicalPrivateIdentity = null,
            )
        viewModel.setImportPublicIdentity("remote-public")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = localPeerId,
                brokerHost = "broker.local",
                remotePeerId = REMOTE_PEER,
            ),
        )
        return viewModel
    }

    private fun clearRelevantStorage() {
        listOf(
            "config.toml",
            "setup_input.json",
            "identity.enc",
            "identity.pub",
            "authorized_keys",
            "forwards.json",
        ).forEach { File(app.filesDir, it).delete() }
        File(app.filesDir, "runtime").deleteRecursively()
    }

    private companion object {
        const val LOCAL_PEER = "android-phone"
        const val REMOTE_PEER = "remote-peer"
    }
}
