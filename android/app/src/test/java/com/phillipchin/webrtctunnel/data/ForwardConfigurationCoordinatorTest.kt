package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * FIX8 P0-005-D/F: [ForwardConfigurationCoordinator]'s exact snapshot/rollback behavior —
 * restoring forwards and config to their precise prior bytes, continuing rollback after one
 * stage's restore fails, and reporting every failed restore rather than only the first.
 */
@RunWith(RobolectricTestRunner::class)
class ForwardConfigurationCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val forwardsFile = File(context.filesDir, "forwards.json")
    private val configFile = File(context.filesDir, "config.toml")

    @Before
    fun setUp() {
        forwardsFile.delete()
        configFile.delete()
    }

    private fun forward(
        id: String,
        port: Int,
    ) = ForwardConfig(id = id, name = id, localPort = port, remoteForwardId = id, enabled = true)

    private fun forwardsRepository(): ForwardsRepository =
        ForwardsRepository(ForwardsConfigStore(context), AppDispatchers()).also { runBlocking { it.refresh() } }

    /** Performs the real atomic move, then throws — the destination has already been updated
     * to the new bytes by the time this fires. */
    private class FailAfterMoveOps : AtomicConfigFileOps by RealAtomicConfigFileOps {
        override fun atomicMove(
            temp: Path,
            destination: Path,
        ) {
            RealAtomicConfigFileOps.atomicMove(temp, destination)
            throw IOException("simulated post-move failure")
        }
    }

    private fun ForwardConfigurationRollbackStageResult.stage(): ForwardConfigurationStage =
        when (this) {
            is ForwardConfigurationRollbackStageResult.Success -> stage
            is ForwardConfigurationRollbackStageResult.Skipped -> stage
            is ForwardConfigurationRollbackStageResult.Failure -> stage
        }

    @Test
    fun forwardConfigFailureRestoresExactPreviousForwardsAndConfig() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository()
            val priorForwardsBytes = forwardsFile.readBytes()
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))
                }
            val coordinator = ForwardConfigurationCoordinator(forwardsRepo, failingConfig)

            val result = coordinator.apply(listOf(forward("new", 2222)), "format = \"new\"\n")

            assertTrue(result is ForwardConfigurationResult.Failed)
            val failed = result as ForwardConfigurationResult.Failed
            assertEquals(ForwardConfigurationStage.Config, failed.failedStage)
            assertEquals(
                listOf(ForwardConfigurationStage.Config, ForwardConfigurationStage.Forwards),
                failed.rollback.map { it.stage() },
            )
            assertTrue(failed.rollback.all { it is ForwardConfigurationRollbackStageResult.Success })
            assertArrayEquals(
                "Config failing after Forwards committed must restore forwards' exact prior bytes",
                priorForwardsBytes,
                forwardsFile.readBytes(),
            )
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
            assertFalse("config.toml must never have been created by the failed write", configFile.exists())
        }

    @Test
    fun forwardConfigCleanupFailureAfterMoveRestoresExactPreviousForwardsAndConfig() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository()
            val priorForwardsBytes = forwardsFile.readBytes()
            configFile.writeText("format = \"old\"\n")
            val priorConfigBytes = configFile.readBytes()
            val postMoveFailingConfig = ConfigRepository(context, FailAfterMoveOps())
            val coordinator = ForwardConfigurationCoordinator(forwardsRepo, postMoveFailingConfig)

            val result = coordinator.apply(listOf(forward("new", 2222)), "format = \"new\"\n")

            assertTrue(result is ForwardConfigurationResult.Failed)
            assertEquals(ForwardConfigurationStage.Config, (result as ForwardConfigurationResult.Failed).failedStage)
            assertArrayEquals(
                "the real move changed config.toml; rollback must revert it to the exact prior bytes",
                priorConfigBytes,
                configFile.readBytes(),
            )
            assertArrayEquals(priorForwardsBytes, forwardsFile.readBytes())
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
        }

    @Test
    fun forwardCancellationDuringConfigRestoresBothResources() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository()
            val priorForwardsBytes = forwardsFile.readBytes()
            configFile.writeText("format = \"old\"\n")
            val priorConfigBytes = configFile.readBytes()
            val cancellingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        throw CancellationException("config write cancelled")
                }
            val coordinator = ForwardConfigurationCoordinator(forwardsRepo, cancellingConfig)

            var caught: CancellationException? = null
            try {
                coordinator.apply(listOf(forward("new", 2222)), "format = \"new\"\n")
            } catch (cancelled: CancellationException) {
                caught = cancelled
            }

            assertTrue("cancellation during Config must propagate", caught != null)
            assertArrayEquals(priorConfigBytes, configFile.readBytes())
            assertArrayEquals(priorForwardsBytes, forwardsFile.readBytes())
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
        }

    @Test
    fun forwardRollbackContinuesAfterOneRestoreFailure() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo = forwardsRepository()
            val priorForwardsBytes = forwardsFile.readBytes()
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))

                    override suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
                        Result.failure(IOException("config restore also failed"))
                }
            val coordinator = ForwardConfigurationCoordinator(forwardsRepo, failingConfig)

            val result = coordinator.apply(listOf(forward("new", 2222)), "format = \"new\"\n")

            assertTrue(result is ForwardConfigurationResult.Failed)
            val rollback = (result as ForwardConfigurationResult.Failed).rollback
            val configRollback = rollback.single { it.stage() == ForwardConfigurationStage.Config }
            val forwardsRollback = rollback.single { it.stage() == ForwardConfigurationStage.Forwards }
            assertTrue(
                "Config's own restore failure must be reported, not hidden",
                configRollback is ForwardConfigurationRollbackStageResult.Failure,
            )
            assertTrue(
                "the Forwards stage restore must still run despite Config's restore failing first",
                forwardsRollback is ForwardConfigurationRollbackStageResult.Success,
            )
            assertArrayEquals(priorForwardsBytes, forwardsFile.readBytes())
            assertEquals(listOf(forward("prior", 1111)), forwardsRepo.current())
        }

    /** Delegates every call to [real] except [restoreExactSnapshot], which always fails — drives
     * a Forwards-stage rollback-restore failure while its initial apply succeeded normally. */
    private class RestoreAlwaysFailsStore(
        private val real: ForwardsStore,
    ) : ForwardsStore by real {
        override fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
            Result.failure(IOException("simulated forwards restore failure"))
    }

    @Test
    fun forwardRollbackIncompleteListsEveryFailedRestore() =
        runBlocking {
            val realStore = ForwardsConfigStore(context)
            realStore.saveForwards(listOf(forward("prior", 1111)))
            val forwardsRepo =
                ForwardsRepository(RestoreAlwaysFailsStore(realStore), AppDispatchers())
                    .also { it.refresh() }
            val failingConfig =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                        Result.failure(IOException("config commit failed"))

                    override suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
                        Result.failure(IOException("config restore also failed"))
                }
            val coordinator = ForwardConfigurationCoordinator(forwardsRepo, failingConfig)

            val result = coordinator.apply(listOf(forward("new", 2222)), "format = \"new\"\n")

            assertTrue(result is ForwardConfigurationResult.Failed)
            val rollback = (result as ForwardConfigurationResult.Failed).rollback
            assertEquals(
                "every attempted stage's failed restore must be listed, not just the first",
                listOf(ForwardConfigurationStage.Config, ForwardConfigurationStage.Forwards),
                rollback.filterIsInstance<ForwardConfigurationRollbackStageResult.Failure>().map { it.stage },
            )
        }
}
