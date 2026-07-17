package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class ForwardsViewModelTest : AppViewModelTestBase() {
    @Test
    fun forwardsViewModelSaveAddsForwardAndReportsResult() {
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        // The save path now reads the debug-logs preference from DataStore (async),
        // so await the result while idling the looper rather than asserting synchronously.
        awaitMessage(vm) { it == "Forward saved" }
        assertTrue(vm.forwards.value.any { it.id == "web" })
        assertFalse(vm.isBusy.value)
    }

    @Test
    fun forwardsViewModelSaveRollsBackOnInvalidConfig() {
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(false, "bad config")
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it?.contains("bad config") == true }
        assertTrue(vm.forwards.value.none { it.id == "web" })
        assertFalse(vm.isBusy.value)
    }

    @Test
    fun forwardsViewModelSaveUsesIdentityAwareValidationWhenIdentityReadable() {
        deps.identityRepository.storeEncryptedIdentity("private-identity-bytes".toByteArray(), "canon-pub")
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it == "Forward saved" }
        assertEquals(1, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
    }

    @Test
    fun forwardsViewModelSaveUsesIdentityLessValidationWhenNoIdentity() {
        assertFalse(deps.identityRepository.hasEncryptedIdentity())
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it == "Forward saved" }
        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(1, recordingBridge.validateConfigCalls)
    }

    @Test
    fun forwardsViewModelSaveReportsVisibleFailureWhenIdentityPresentButUnreadable() {
        val unreadableIdentityRepository =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = error("decrypt boom")
                },
            )
        unreadableIdentityRepository.storeEncryptedIdentity("garbage".toByteArray(), "canon-pub")
        val brokenDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository = unreadableIdentityRepository,
                dispatchers = deps.dispatchers,
            )
        val vm = ForwardsViewModel(brokenDeps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it?.contains("Identity exists but could not be loaded") == true }
        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
        assertTrue(vm.forwards.value.none { it.id == "web" })
    }

    @Test
    fun forwardsViewModelSaveSurfacesRollbackFailureWhenRollbackPersistenceFails() {
        // Inline (Unconfined) dispatchers would run save-to-rollback start-to-finish with
        // no window to interleave a filesystem change, so use real IO dispatchers here —
        // matching LogsViewModelTest's concurrentExportIsRejectedWhileOneIsAlreadyInFlight
        // pattern — to genuinely suspend at withContext(ioDispatcher).
        val realIoDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = realIoTestDispatchers(),
            )
        val vm = ForwardsViewModel(realIoDeps)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        recordingBridge.blockNextValidateConfig()
        vm.saveForward(forward)
        // The upsert() call itself hops to a real IO dispatcher and back before
        // regenerateActiveConfig() runs, so the launch must be resumed on the (Robolectric
        // shadow) main looper in between — pump it while waiting for entry rather than
        // blocking this thread, which is the only thread that can drain that queue.
        awaitCondition { recordingBridge.validateConfigEnteredNow() }
        // Mutation persistence (the upsert) happens before regenerateActiveConfig() calls
        // into validation, so reaching the blocked call proves it already succeeded.
        assertTrue(realIoDeps.forwardsRepository.current().any { it.id == "web" })

        // Make rollback persistence fail: the real ForwardsConfigStore writes forwards.json
        // under filesDir, so making that directory unwritable forces its temp-file create to
        // throw — no production hook needed, per the TODO's instruction.
        assertTrue(app.filesDir.setWritable(false))
        try {
            recordingBridge.releaseBlockedValidateConfig(ValidationResult(false, "bad config"))
            awaitMessage(vm) { it != null }
        } finally {
            app.filesDir.setWritable(true)
        }

        val message = requireNotNull(vm.message.value)
        assertTrue("expected original failure in: $message", message.contains("bad config"))
        assertTrue("expected rollback failure in: $message", message.contains("Rollback also failed"))
        assertTrue(
            "expected consistency-state wording in: $message",
            message.contains("remains saved") && message.contains("not activated"),
        )
    }

    @Test
    fun forwardsViewModelDeleteSurfacesRollbackFailureWhenRollbackPersistenceFails() {
        val realIoDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = realIoTestDispatchers(),
            )
        val vm = ForwardsViewModel(realIoDeps)
        // Seeded default forwards include "ssh"; delete it rather than an added one.
        assertTrue(realIoDeps.forwardsRepository.current().any { it.id == "ssh" })

        recordingBridge.blockNextValidateConfig()
        vm.deleteForward("ssh")
        awaitCondition { recordingBridge.validateConfigEnteredNow() }
        assertTrue(realIoDeps.forwardsRepository.current().none { it.id == "ssh" })

        assertTrue(app.filesDir.setWritable(false))
        try {
            recordingBridge.releaseBlockedValidateConfig(ValidationResult(false, "bad config"))
            awaitMessage(vm) { it != null }
        } finally {
            app.filesDir.setWritable(true)
        }

        val message = requireNotNull(vm.message.value)
        assertTrue("expected original failure in: $message", message.contains("bad config"))
        assertTrue("expected rollback failure in: $message", message.contains("Rollback also failed"))
        assertTrue(
            "expected consistency-state wording in: $message",
            message.contains("remains saved") && message.contains("not activated"),
        )
    }

    @Test
    fun loadErrorIsVisibleWhenSavedForwardsFileIsCorruptAndClearsOnSuccessfulRetry() {
        val forwardsFile = File(app.filesDir, "forwards.json")
        forwardsFile.writeText("{ corrupt json")
        val corruptDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = deps.dispatchers,
            )
        val vm = ForwardsViewModel(corruptDeps)

        // A corrupt initial load must be visible on the ViewModel, not silently rendered
        // as a legitimately empty forwards list (P1-002).
        assertTrue(vm.loadError.value != null)
        assertTrue(vm.forwards.value.isEmpty())
        // The saved file must be left untouched, not overwritten with fresh defaults.
        assertTrue(forwardsFile.readText().contains("corrupt"))

        // Retry (vm.reload()) after fixing the file clears the error.
        forwardsFile.writeText("[]")
        vm.reload()
        awaitCondition { vm.loadError.value == null }
        assertTrue(vm.forwards.value.isEmpty())
    }

    @Test
    fun forwardsViewModelTestLocalPortReportsSuccessAndFailure() {
        runBlocking {
            val server = ServerSocket(0)
            val successVm = ForwardsViewModel(deps)
            val successForward =
                ForwardConfig(
                    id = "svc-open",
                    name = "svc-open",
                    localHost = "127.0.0.1",
                    localPort = server.localPort,
                    remoteForwardId = "svc-open",
                    enabled = true,
                )
            val successMessage =
                async {
                    withTimeout(5_000) {
                        successVm.message.first { it?.contains("succeeded") == true }
                    }
                }
            successVm.testLocalPort(successForward)
            assertTrue(successMessage.await()?.contains("succeeded") == true)
            server.close()

            val failureVm = ForwardsViewModel(deps)
            val failureForward = successForward.copy(id = "svc-closed", localPort = successForward.localPort)
            val failureMessage =
                async {
                    withTimeout(5_000) {
                        failureVm.message.first { it?.contains("failed") == true }
                    }
                }
            failureVm.testLocalPort(failureForward)
            assertTrue(failureMessage.await()?.contains("failed") == true)
        }
    }

    // Drive the Robolectric main looper while waiting for an async save result, so
    // viewModelScope coroutines actually run instead of sitting queued.
    private fun awaitMessage(
        vm: ForwardsViewModel,
        predicate: (String?) -> Boolean,
    ) {
        runBlocking {
            withTimeout(5_000) {
                while (!predicate(vm.message.value)) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        runBlocking {
            withTimeout(10_000) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }

    // FIX6 P0-001-D: config validation succeeds but the atomic config write fails. Before
    // this fix regenerateActiveConfig discarded the write result, so the forward save
    // reported success while config.toml was unchanged. Now the write failure invalidates
    // the sync result, which drives the existing receipt rollback.

    private class WriteFailingConfigRepository(
        context: android.content.Context,
        private val onWrite: () -> Result<Unit>,
    ) : ConfigRepository(context) {
        override suspend fun writeConfigAtomically(contents: String): Result<Unit> = onWrite()
    }

    private fun forwardsViewModelWith(configRepository: ConfigRepository): ForwardsViewModel =
        ForwardsViewModel(
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            ),
        )

    @Test
    fun configWriteFailureRollsBackForwardMutation() {
        val vm =
            forwardsViewModelWith(
                WriteFailingConfigRepository(app) { Result.failure(java.io.IOException("disk full")) },
            )
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it != null }
        assertTrue(
            "a failed config commit must roll the forward mutation back",
            vm.forwards.value.none { it.id == "web" },
        )
    }

    @Test
    fun configWriteFailureDoesNotReportForwardSaved() {
        val vm =
            forwardsViewModelWith(
                WriteFailingConfigRepository(app) { Result.failure(java.io.IOException("disk full")) },
            )
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.saveForward(
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true),
        )

        awaitMessage(vm) { it != null }
        assertFalse("a failed config commit must not report the forward as saved", vm.message.value == "Forward saved")
    }

    @Test
    fun configWriteFailureReportsActivationFailure() {
        val vm =
            forwardsViewModelWith(
                // Null message exercises the fixed fallback text.
                WriteFailingConfigRepository(app) { Result.failure(java.io.IOException()) },
            )
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.saveForward(
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true),
        )

        awaitMessage(vm) { it != null }
        assertTrue(
            "the config-write failure must be surfaced, not swallowed: ${vm.message.value}",
            vm.message.value?.contains("Failed to write active config") == true,
        )
    }

    @Test
    fun configWriteFailureWithNewerRevisionDoesNotOverwriteNewerForwards() {
        // A blocking, ultimately-failing write gives a window to commit a newer forward
        // mutation before the rollback runs. The rollback then sees a bumped revision and
        // must skip reverting so the newer change survives.
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockingRepo =
            object : ConfigRepository(app) {
                override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
                    entered.countDown()
                    release.await()
                    return Result.failure(java.io.IOException("disk full"))
                }
            }
        val realIoDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = blockingRepo,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = realIoTestDispatchers(),
            )
        val vm = ForwardsViewModel(realIoDeps)
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.saveForward(
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true),
        )
        // Wait until the save is blocked inside the config write — its forward upsert has
        // already committed at this point, capturing that revision in the rollback receipt.
        awaitCondition { entered.count == 0L }
        assertTrue(realIoDeps.forwardsRepository.current().any { it.id == "web" })

        // Commit a newer forward, bumping the revision past the receipt's.
        runBlocking {
            realIoDeps.forwardsRepository
                .upsertWithReceipt(
                    ForwardConfig(id = "api", name = "api", localPort = 9091, remoteForwardId = "api", enabled = true),
                ).getOrThrow()
        }

        release.countDown()
        awaitMessage(vm) { it != null }

        assertTrue(
            "rollback must be skipped on a revision mismatch: ${vm.message.value}",
            vm.message.value?.contains("forwards changed again") == true,
        )
        assertTrue("the newer forward must survive", realIoDeps.forwardsRepository.current().any { it.id == "api" })
        assertTrue(
            "the un-rolled-back forward must remain since revert was skipped",
            realIoDeps.forwardsRepository.current().any { it.id == "web" },
        )
    }
}
