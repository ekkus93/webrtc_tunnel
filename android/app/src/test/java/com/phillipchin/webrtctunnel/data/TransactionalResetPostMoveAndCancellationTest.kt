package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
 * FIX8 P0-006-E: non-UTF-8 byte-exactness, post-move failures for the SetupInput/Forwards reset
 * stages specifically, per-stage cancellation, and secret-wipe coverage across every outcome —
 * split out of the other TransactionalReset* test files to keep them under the repo's 800-line
 * guidance.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionalResetPostMoveAndCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configFile = File(context.filesDir, "config.toml")
    private val setupInputFile = File(context.filesDir, "setup_input.json")
    private val forwardsFile = File(context.filesDir, "forwards.json")

    @Before
    fun setUp() {
        configFile.delete()
        setupInputFile.delete()
        forwardsFile.delete()
    }

    private fun forward(id: String) =
        ForwardConfig(id = id, name = id, localPort = 9999, remoteForwardId = id, enabled = true)

    private fun readyForwardsRepo(store: ForwardsStore = ForwardsConfigStore(context)): ForwardsRepository =
        ForwardsRepository(store, AppDispatchers()).also { runBlocking { it.refresh() } }

    /** Performs the real atomic move, then throws on its [failOn]-th call (1-indexed) — used to
     * target a specific reset stage's write (Config = call 1, SetupInput = call 2) while leaving
     * earlier calls on the same repository succeeding normally. */
    private class FailNthMoveOps(private val failOn: Int) : AtomicConfigFileOps by RealAtomicConfigFileOps {
        private var moveCount = 0

        override fun atomicMove(
            temp: Path,
            destination: Path,
        ) {
            RealAtomicConfigFileOps.atomicMove(temp, destination)
            moveCount++
            if (moveCount == failOn) throw IOException("simulated post-move failure #$failOn")
        }
    }

    @Test
    fun nonUtf8ConfigSnapshotRestoresExactBytesAfterResetFailure() =
        runBlocking {
            // Bytes that are not valid UTF-8 (a lone continuation byte) — a capture that
            // round-trips through a UTF-8 String would corrupt these on restore.
            val nonUtf8Bytes = byteArrayOf(0x66, 0x6f, 0x6f, 0x80.toByte(), 0x01, 0x02)
            configFile.parentFile?.mkdirs()
            configFile.writeBytes(nonUtf8Bytes)
            val forwardsRepo = readyForwardsRepo()
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
                        Result.failure(IOException("setup reset failed"))
                }
            val coordinator = TransactionalResetCoordinator(failingConfig, forwardsRepo)

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            assertEquals(ResetStage.SetupInput, (result as ResetResult.Failed).failedStage)
            assertArrayEquals(
                "non-UTF-8 config bytes must be restored exactly, not corrupted by a String round trip",
                nonUtf8Bytes,
                configFile.readBytes(),
            )
        }

    @Test
    fun resetSetupInputPostMoveFailureRestoresCurrentStageAndConfig() =
        runBlocking {
            val priorConfig = "format = \"prior\"\n"
            configFile.parentFile?.mkdirs()
            configFile.writeText(priorConfig)
            val priorSetupInput = "{\n  \"brokerHost\": \"broker.prior\"\n}\n"
            setupInputFile.writeText(priorSetupInput)
            val forwardsRepo = readyForwardsRepo()
            // Call 1 = Config's own reset write (succeeds). Call 2 = SetupInput's own reset
            // write — the real move happens, then this reports failure afterward.
            val repo = ConfigRepository(context, FailNthMoveOps(failOn = 2))
            val coordinator = TransactionalResetCoordinator(repo, forwardsRepo)

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            assertEquals(ResetStage.SetupInput, (result as ResetResult.Failed).failedStage)
            assertEquals(
                "the real move changed setup_input.json; rollback must revert it to the exact prior bytes",
                priorSetupInput,
                setupInputFile.readText(),
            )
            assertEquals(
                "Config committed before the failing SetupInput stage must also be restored",
                priorConfig,
                configFile.readText(),
            )
        }

    /** Delegates every call to [real] except [saveForwards], which performs the real save (so the
     * file genuinely changes) and then throws — a post-move failure at the Forwards reset stage
     * itself, distinct from a failure that never touches disk. */
    private class PostMoveFailingForwardsStore(
        private val real: ForwardsStore,
    ) : ForwardsStore by real {
        override fun saveForwards(forwards: List<ForwardConfig>) {
            real.saveForwards(forwards)
            throw IOException("simulated post-move forwards failure")
        }
    }

    @Test
    fun resetForwardsPostMoveFailureRestoresCurrentStageSetupInputAndConfig() =
        runBlocking {
            val priorConfig = "format = \"prior\"\n"
            configFile.parentFile?.mkdirs()
            configFile.writeText(priorConfig)
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior")))
            val forwardsRepo = readyForwardsRepo(PostMoveFailingForwardsStore(realStore))
            val priorForwardsBytes = forwardsFile.readBytes()
            val repo = ConfigRepository(context)
            repo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior"))
            val priorSetupInput = setupInputFile.readText()
            val coordinator = TransactionalResetCoordinator(repo, forwardsRepo)

            val result = coordinator.resetConfiguration()

            assertTrue(result is ResetResult.Failed)
            assertEquals(ResetStage.Forwards, (result as ResetResult.Failed).failedStage)
            assertArrayEquals(
                "the real save changed forwards.json; rollback must revert it to the exact prior bytes",
                priorForwardsBytes,
                forwardsFile.readBytes(),
            )
            assertEquals(
                "SetupInput committed before the failing Forwards stage must also be restored",
                priorSetupInput,
                setupInputFile.readText(),
            )
            assertEquals(
                "Config committed before the failing Forwards stage must also be restored",
                priorConfig,
                configFile.readText(),
            )
        }

    @Test
    fun resetCancellationDuringEachStageRestoresCurrentAndEarlierStages() =
        runBlocking {
            // Cancellation during Config: nothing earlier to restore, just propagate.
            run {
                val cancellingConfig =
                    object : ConfigRepository(context) {
                        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                            throw CancellationException("cancelled during config")
                    }
                var caught: CancellationException? = null
                try {
                    TransactionalResetCoordinator(cancellingConfig, readyForwardsRepo()).resetConfiguration()
                } catch (cancelled: CancellationException) {
                    caught = cancelled
                }
                assertTrue("cancellation during Config must propagate", caught != null)
            }

            // Cancellation during SetupInput: Config (already reset to defaults) must be
            // restored back to its prior state.
            run {
                configFile.writeText("format = \"prior-setup\"\n")
                val cancellingSetup =
                    object : ConfigRepository(context) {
                        override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
                            throw CancellationException("cancelled during setup input")
                    }
                var caught: CancellationException? = null
                try {
                    TransactionalResetCoordinator(cancellingSetup, readyForwardsRepo()).resetConfiguration()
                } catch (cancelled: CancellationException) {
                    caught = cancelled
                }
                assertTrue("cancellation during SetupInput must propagate", caught != null)
                assertEquals(
                    "Config committed before the cancelled SetupInput stage must be rolled back",
                    "format = \"prior-setup\"\n",
                    configFile.readText(),
                )
            }

            // Cancellation during Forwards: Config and SetupInput must both be restored.
            run {
                configFile.writeText("format = \"prior-forwards\"\n")
                val repo = ConfigRepository(context)
                repo.saveSetupInput(SetupConfigInput(brokerHost = "broker.prior-forwards"))
                val cancellingForwardsStore =
                    object : ForwardsStore by ForwardsConfigStore(context) {
                        override fun saveForwards(forwards: List<ForwardConfig>): Unit =
                            throw CancellationException("cancelled during forwards")
                    }
                var caught: CancellationException? = null
                try {
                    TransactionalResetCoordinator(repo, readyForwardsRepo(cancellingForwardsStore))
                        .resetConfiguration()
                } catch (cancelled: CancellationException) {
                    caught = cancelled
                }
                assertTrue("cancellation during Forwards must propagate", caught != null)
                assertEquals("format = \"prior-forwards\"\n", configFile.readText())
                assertEquals("broker.prior-forwards", repo.loadSetupInputResult().getOrThrow().brokerHost)
            }
        }

    @Test
    fun resetSnapshotSecretBytesWipedAfterSuccessFailureCancellationAndFatalError() =
        runBlocking {
            fun trackedBytesCoordinator(
                config: ConfigRepository,
                trackedBytes: ByteArray,
            ) = TransactionalResetCoordinator(config, readyForwardsRepo(), setupInputReadBytes = { trackedBytes })

            // Success.
            run {
                setupInputFile.writeText("prior")
                val tracked = "prior-success".toByteArray()
                val result = trackedBytesCoordinator(ConfigRepository(context), tracked).resetConfiguration()
                assertTrue(result is ResetResult.Success)
                assertTrue("bytes must be wiped after success", tracked.all { it == 0.toByte() })
            }

            // Ordinary failure.
            run {
                setupInputFile.writeText("prior")
                val tracked = "prior-failure".toByteArray()
                val failingConfig =
                    object : ConfigRepository(context) {
                        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                            Result.failure(IOException("boom"))
                    }
                val result = trackedBytesCoordinator(failingConfig, tracked).resetConfiguration()
                assertTrue(result is ResetResult.Failed)
                assertTrue("bytes must be wiped after an ordinary failure", tracked.all { it == 0.toByte() })
            }

            // Cancellation.
            run {
                setupInputFile.writeText("prior")
                val tracked = "prior-cancel".toByteArray()
                val cancellingConfig =
                    object : ConfigRepository(context) {
                        override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                            throw CancellationException("cancelled")
                    }
                try {
                    trackedBytesCoordinator(cancellingConfig, tracked).resetConfiguration()
                } catch (ignored: CancellationException) {
                    // expected
                }
                assertTrue("bytes must be wiped after a cancellation", tracked.all { it == 0.toByte() })
            }

            // Fatal (uncaught, non-Cancellation) error from a stage.
            run {
                setupInputFile.writeText("prior")
                val tracked = "prior-fatal".toByteArray()
                val fatalConfig =
                    object : ConfigRepository(context) {
                        override suspend fun writeConfigAtomically(contents: String): Result<Unit> = error("fatal boom")
                    }
                try {
                    trackedBytesCoordinator(fatalConfig, tracked).resetConfiguration()
                } catch (ignored: IllegalStateException) {
                    // expected — an uncaught RuntimeException from a stage propagates
                }
                assertTrue("bytes must be wiped even after an uncaught fatal error", tracked.all { it == 0.toByte() })
            }
        }
}
