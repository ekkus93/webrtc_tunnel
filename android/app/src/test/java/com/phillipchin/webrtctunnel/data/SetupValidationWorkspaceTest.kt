package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * FIX7 P0-003-C: [withSetupValidationWorkspace] in isolation — [SetupSaveAuthorizedKeysTest]
 * covers the full end-to-end contract (validation never touches live storage) through the real
 * [com.phillipchin.webrtctunnel.viewmodel.SetupSaveController] flow.
 */
@RunWith(RobolectricTestRunner::class)
class SetupValidationWorkspaceTest {
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "workspaces")
        cacheDir.deleteRecursively()
    }

    @Test
    fun setupValidationUsesUniqueWorkspaceForConcurrentAttempts() =
        runBlocking {
            var first: File? = null
            var second: File? = null
            withSetupValidationWorkspace(cacheDir, includeBrokerPassword = false) { workspace ->
                first = workspace.root
            }
            withSetupValidationWorkspace(cacheDir, includeBrokerPassword = false) { workspace ->
                second = workspace.root
            }
            assertNotEquals(
                "two validation attempts must never share one workspace directory",
                first?.absolutePath,
                second?.absolutePath,
            )
        }

    @Test
    fun setupValidationWorkspaceCleanupFailurePreventsCommitAndIsVisible() {
        val failingDelete: (File) -> Result<Unit> = {
            Result.failure(java.io.IOException("simulated workspace cleanup failure"))
        }
        var blockRan = false

        val thrown =
            try {
                runBlocking {
                    withSetupValidationWorkspace(cacheDir, includeBrokerPassword = false, failingDelete) {
                        blockRan = true
                        "would-be validation success"
                    }
                }
                null
            } catch (cleanupFailure: CandidateCleanupException) {
                cleanupFailure
            }

        // The block itself (rendering + validating the candidate) did run and would have
        // returned success — but the caller (SetupSaveController.validateInIsolatedWorkspace)
        // catches CandidateCleanupException specifically and turns it into a durable, visible
        // `candidate_cleanup_failed` save failure instead of ever reaching the commit step.
        assertTrue("the validation block itself must have run", blockRan)
        assertTrue("cleanup failure must surface as a distinct, visible exception type", thrown != null)
    }
}
