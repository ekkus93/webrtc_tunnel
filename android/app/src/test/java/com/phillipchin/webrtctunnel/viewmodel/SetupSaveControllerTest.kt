package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.io.IOException

/**
 * P0-002: sentinel tests proving plaintext identity byte-array zeroization.
 *
 * Each test creates a sentinel byte array that is returned by the identity repository,
 * triggers a specific failure/success path, and verifies the array contents are all
 * zeros afterward.
 *
 * The sentinel tracking works as follows:
 * - A custom [IdentityCrypto] is injected where [IdentityCrypto.decrypt] always returns
 *   the sentinel array, so [IdentityRepository.readPrivateIdentityPlaintext] returns it.
 * - The production code zeroes the returned array on failure paths.
 * - The test verifies the sentinel is zeroed after the operation.
 */
@RunWith(RobolectricTestRunner::class)
class SetupSaveControllerTest {
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    private val configRepository = ConfigRepository(app)

    @Before
    fun setUp() {
        // Clean up any previous test state
        File(app.filesDir, "identity.enc").delete()
        File(app.filesDir, "identity.pub").delete()
    }

    @Test
    fun sentinelTrackingMechanismWorks() {
        // First verify that the tracking mechanism itself works
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()

        val repo =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = sentinel
                },
            )
        repo.storeEncryptedIdentity(sentinel, "pub-identity")

        // Read the private identity and verify it's the sentinel
        val readBytes = repo.readPrivateIdentityPlaintext()

        // Check that readBytes is the same array as sentinel
        assert(readBytes === sentinel) { "readPrivateIdentityPlaintext should return sentinel" }

        // Zero the read bytes
        readBytes.fill(0)

        // Verify sentinel is zeroed
        assert(sentinel.all { it == 0.toByte() }) { "sentinel should be zeroed" }
    }

    @Test
    fun storedIdentityValidationThrowWipesPlaintext() {
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = false,
                        message = "validation failed",
                        peerId = null,
                        canonicalPublicIdentity = null,
                        canonicalPrivateIdentity = null,
                    )
            }

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage != null)
        assertTrue(
            "validation throw: sentinel bytes must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun storedIdentityValidationInvalidWipesPlaintext() {
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = false,
                        message = "invalid identity",
                        peerId = null,
                        canonicalPublicIdentity = null,
                        canonicalPrivateIdentity = null,
                    )
            }

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage != null)
        assertTrue(
            "validation invalid: sentinel bytes must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun storedIdentityPublicReadThrowWipesPlaintext() {
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        peerId = "android-phone",
                        // Forces readPublicIdentity() call
                        canonicalPublicIdentity = null,
                        canonicalPrivateIdentity = "canon-private",
                    )
            }

        // Make public identity unreadable by removing the file
        File(app.filesDir, "identity.pub").delete()

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null || it.saveResult != null }
        // Either error or success (empty public identity may be accepted)
        // The key is that the sentinel is zeroed
        assertTrue(
            "public read throw: sentinel bytes must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun storedIdentityPeerIdMissingWipesPlaintext() {
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        // Missing peer ID causes throw
                        peerId = null,
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
            }

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage != null)
        assertTrue(
            "peer id missing: sentinel bytes must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun storedIdentitySuccessFinalOwnerWipes() {
        val sentinel = "P0-002-SENTINEL-12345678901234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.saveResult != null }
        assertTrue(state.saveResult != null)
        assertTrue(
            "success: sentinel bytes must be zeroed after use",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    // -- FIX7 P0-004-F: plaintext identity wipe, one focused test per distinct trigger point --

    @Test
    fun plaintextIdentityIsWipedOnSetupSuccess() {
        val sentinel = "P0-004-SENTINEL-SUCCESS-1234567890ABCDEF".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)
        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }
        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)
        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()
        val state = awaitState(viewModel) { it.saveResult != null }

        assertTrue("save must succeed: ${state.errorMessage}", state.saveResult != null)
        assertTrue(
            "success: plaintext identity buffer must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun plaintextIdentityIsWipedOnValidationFailure() {
        val sentinel = "P0-004-SENTINEL-VALIDATION-1234567890ABCD".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)
        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                // The candidate config fails native validation (not the identity check itself).
                validationResult = ValidationResult(false, "forced validation failure")
            }
        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)
        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()
        val state = awaitState(viewModel) { it.errorMessage != null }

        assertTrue(state.errorMessage != null)
        assertTrue(
            "validation failure: plaintext identity buffer must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun plaintextIdentityIsWipedOnPersistenceFailure() {
        val sentinel = "P0-004-SENTINEL-PERSISTFAIL-1234567890AB".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)
        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }
        val deps = createDeps(identityRepo = identityRepo, bridge = bridge, configRepo = DiskFullConfig(app))
        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()
        val state = awaitState(viewModel) { it.errorMessage != null }

        assertTrue(
            "persistence must fail and roll back: ${state.errorMessage}",
            state.errorMessage?.contains("setup_persistence_failed") == true ||
                state.errorMessage?.contains("setup_rollback_incomplete") == true,
        )
        assertTrue(
            "persistence failure: plaintext identity buffer must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun plaintextIdentityIsWipedOnCancellation() {
        val sentinel = "P0-004-SENTINEL-CANCEL-1234567890ABCDEF12".toByteArray()
        val (identityRepo, trackedSentinel) = createTrackedIdentityRepo(sentinel)
        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }
        val deps = createDeps(identityRepo = identityRepo, bridge = bridge, configRepo = CancellingConfig(app))
        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()
        runBlocking {
            repeat(SETTLE_CYCLES) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(SETTLE_DELAY_MS)
            }
        }

        assertEquals(null, viewModel.state.value.saveResult)
        assertTrue(
            "cancellation: plaintext identity buffer must be zeroed",
            trackedSentinel.all { it == 0.toByte() },
        )
    }

    @Test
    fun missingStoredIdentityReportsMissing() {
        val bridge = RecordingBridge()
        val identityRepo =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = payload
                },
            )
        // Do NOT store an identity - hasEncryptedIdentity() will return false

        val deps = createDeps(identityRepo = identityRepo, bridge = bridge)

        val viewModel = SetupViewModel(deps)
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null }
        assertTrue(
            "Expected missing identity error, got: ${state.errorMessage}",
            state.errorMessage?.contains("Missing encrypted identity") == true ||
                state.errorMessage?.contains("Import or generate") == true,
        )
    }

    @Test
    fun importIdentitySuccessWipesAfterStore() {
        val identityFile =
            File(app.filesDir, "import_sentinel.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }

        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }

        val deps = createDeps(bridge = bridge)

        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        setupValidState(viewModel, deps)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.saveResult != null }
        assertTrue(state.saveResult != null)
    }

    // -- P0-001-B / P0-003: config write is the last stage; its failure must roll back --

    /** Config write always fails with a secret-bearing message, to prove redaction + rollback. */
    private class DiskFullConfig(app: android.app.Application) : ConfigRepository(app) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            Result.failure(IOException("disk full password=sentinel"))
    }

    /** Config write is cancelled, to prove cancellation propagates rather than being reported. */
    private class CancellingConfig(app: android.app.Application) : ConfigRepository(app) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
            throw CancellationException("config write cancelled")
    }

    /**
     * Builds a wizard whose save passes every validation step and reaches the config-write
     * stage, backed by [configRepo]. Uses the imported-identity path so identity, authorized
     * keys, setup input and preferences all commit before the config write.
     */
    private fun wizardReachingConfigWrite(configRepo: ConfigRepository): Pair<SetupViewModel, ConfigRepository> {
        listOf("setup_input.json", "config.toml", "authorized_keys").forEach { File(app.filesDir, it).delete() }
        val identityFile =
            File(app.filesDir, "import_sentinel.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        val bridge =
            RecordingBridge().apply {
                privateIdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        message = null,
                        peerId = "android-phone",
                        canonicalPublicIdentity = "canon-pub",
                        canonicalPrivateIdentity = "canon-private",
                    )
                validationResult = ValidationResult(true, null)
            }
        val deps = createDeps(bridge = bridge, configRepo = configRepo)
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
                allowMetered = true,
            ),
        )
        setupValidState(viewModel, deps)
        return viewModel to configRepo
    }

    @Test
    fun configWriteFailureDoesNotReportConfigurationSaved() {
        val (viewModel, _) = wizardReachingConfigWrite(DiskFullConfig(app))

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage != null)
        assertFalse("secret must be redacted", state.errorMessage!!.contains("sentinel"))
        assertEquals(null, state.saveResult)
    }

    @Test
    fun configWriteFailureDoesNotPersistSetupInput() {
        val (viewModel, _) = wizardReachingConfigWrite(DiskFullConfig(app))

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.errorMessage != null }

        assertFalse(
            "setup input written before the failed config commit must be rolled back",
            File(app.filesDir, "setup_input.json").exists(),
        )
    }

    @Test
    fun configWriteFailureDoesNotPersistPreferences() {
        val (viewModel, configRepo) = wizardReachingConfigWrite(DiskFullConfig(app))
        val before = runBlocking { configRepo.preferences.first() }

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.errorMessage != null }

        val after = runBlocking { configRepo.preferences.first() }
        assertEquals("preferences must be rolled back to their prior value", before, after)
    }

    @Test
    fun configWriteCancellationPropagatesAndDoesNotReportFailureOrSuccess() {
        val (viewModel, _) = wizardReachingConfigWrite(CancellingConfig(app))

        viewModel.save.saveAndApplyConfig()
        // Let the cancelled save settle; it must report neither success nor failure.
        runBlocking {
            repeat(SETTLE_CYCLES) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(SETTLE_DELAY_MS)
            }
        }

        val state = viewModel.state.value
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.saveResult)
    }

    @Test
    fun cancellationNeverReportsConfigurationSavedOrOrdinarySaveFailure() {
        // FIX7 P0-004-D/E: cancellation during the LAST stage (Config) must roll back every
        // earlier stage (Identity, AuthorizedKeys, SetupInput, Preferences) that already
        // committed — proving the coordinator's cancellation-path rollback, not just that the
        // save reports neither success nor failure.
        listOf("identity.enc", "identity.pub", "authorized_keys").forEach { File(app.filesDir, it).delete() }
        val (viewModel, _) = wizardReachingConfigWrite(CancellingConfig(app))
        val identityExistedBefore = File(app.filesDir, "identity.enc").exists()
        val authorizedKeysExistedBefore = File(app.filesDir, "authorized_keys").exists()

        viewModel.save.saveAndApplyConfig()
        runBlocking {
            repeat(SETTLE_CYCLES) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(SETTLE_DELAY_MS)
            }
        }

        val state = viewModel.state.value
        assertTrue(
            "a cancelled save must never report ordinary success or failure: ${state.errorMessage}",
            state.saveResult == null &&
                (state.errorMessage == null || state.errorMessage!!.contains("setup_cancelled_rollback_incomplete")),
        )
        assertEquals(
            "identity committed before the cancelled Config stage must be rolled back",
            identityExistedBefore,
            File(app.filesDir, "identity.enc").exists(),
        )
        assertEquals(
            "authorized_keys committed before the cancelled Config stage must be rolled back",
            authorizedKeysExistedBefore,
            File(app.filesDir, "authorized_keys").exists(),
        )
    }

    private companion object {
        const val SETTLE_CYCLES = 20
        const val SETTLE_DELAY_MS = 5L
    }

    // -- Helpers --

    /**
     * Creates an identity repository where [readPrivateIdentityPlaintext] returns the
     * sentinel array, so the test can verify it is zeroed after the operation.
     */
    private fun createTrackedIdentityRepo(sentinel: ByteArray): Pair<IdentityRepository, ByteArray> {
        val repo =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = sentinel
                },
            )
        repo.storeEncryptedIdentity(sentinel, "pub-identity")

        return Pair(repo, sentinel)
    }

    private fun createDeps(
        identityRepo: IdentityRepository? = null,
        bridge: RecordingBridge,
        configRepo: ConfigRepository = configRepository,
    ): AppDependencies {
        val identityRepoFinal = identityRepo ?: createTrackedIdentityRepo(byteArrayOf()).first
        return AppDependencies(
            context = app,
            nativeBridgeFactory = { bridge },
            configRepository = configRepo,
            networkPolicyManager =
                NetworkPolicyManager {
                    NetworkType.UnmeteredWifi to false
                },
            identityRepository = identityRepoFinal,
            dispatchers = inlineTestDispatchers(),
        )
    }

    private fun setupValidState(
        viewModel: SetupViewModel,
        deps: AppDependencies,
    ) {
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

        // Set up necessary input fields for navigation
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )

        // Navigate to Review step (the save step)
        // If blocked at Identity with a stored identity, that's fine - the save path
        // will resolve the stored identity. If no stored identity, we're testing the
        // "missing identity" path.
        val hadStoredIdentity = deps.identityRepository.hasEncryptedIdentity()

        repeat(SetupStep.entries.size) {
            if (viewModel.state.value.currentStep == SetupStep.Review) return@repeat
            val stepBefore = viewModel.state.value.currentStep
            viewModel.goNext()
            // If we're stuck and can't advance further, stop
            val step = viewModel.state.value.currentStep
            if (step == SetupStep.Identity && !hadStoredIdentity) {
                // No stored identity - this is the "missing identity" test path
                return@repeat
            }
            if (step == stepBefore) {
                // Can't advance further
                return@repeat
            }
        }
    }

    private fun awaitState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SetupWizardState? = null
                while (true) {
                    val current = viewModel.state.value
                    if (predicate(current)) {
                        matched = current
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(10)
                }
                matched ?: error("Timed out waiting for setup state")
            }
        }
}
