package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryFix9ResultContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        File(context.filesDir, "config.toml").deleteRecursively()
    }

    @Test
    fun savePreferencesOrdinaryExceptionReturnsFailure() =
        runBlocking {
            val repository =
                ConfigRepository(
                    context,
                    preferenceWriter = { throw SecurityException("preference write denied") },
                )

            val result = repository.savePreferences(AndroidAppPreferences())

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
        }

    @Test
    fun savePreferencesCancellationPropagates() =
        runBlocking {
            val repository =
                ConfigRepository(
                    context,
                    preferenceWriter = { throw CancellationException("cancel preference write") },
                )
            var propagated = false

            try {
                repository.savePreferences(AndroidAppPreferences())
            } catch (_: CancellationException) {
                propagated = true
            }

            assertTrue(propagated)
        }

    @Test
    fun prepareActiveConfigReadFailureReturnsFailure() =
        runBlocking {
            val repository = ConfigRepository(context)
            val configPath = File(repository.configPath)
            assertTrue(configPath.mkdirs())

            val result = repository.prepareActiveConfigForStart("native", null)

            assertTrue(result.isFailure)
            assertTrue(configPath.isDirectory)
        }

    @Test
    fun replaceConfigCaptureFailureReturnsFailureAndDoesNotWrite() =
        runBlocking {
            val repository = ConfigRepository(context)
            val configPath = File(repository.configPath)
            assertTrue(configPath.mkdirs())

            val result = repository.replaceConfigTransactionally("replacement")

            assertTrue(result.isFailure)
            assertTrue(configPath.isDirectory)
            assertFalse(File(configPath, "replacement").exists())
        }
}
