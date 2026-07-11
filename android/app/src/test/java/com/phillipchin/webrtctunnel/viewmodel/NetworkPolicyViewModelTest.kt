package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
open class NetworkPolicyViewModelTest : AppViewModelTestBase() {
    private lateinit var viewModel: NetworkPolicyViewModel

    @Before
    override fun setUp() {
        super.setUp()
        viewModel = NetworkPolicyViewModel(deps)
    }

    @Test
    fun savePreferencesSuccessShowsUpdatedMessage() =
        runBlocking {
            val messages = mutableListOf<String>()
            val job =
                launch {
                    deps.snackbar.messages.collect { messages.add(it) }
                }

            viewModel.savePreferences(AndroidAppPreferences())

            withTimeout(5_000) {
                while (messages.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(10)
                }
            }

            assertEquals("Network policy updated", messages.first())
            job.cancel()
        }

    @Test
    fun savePreferencesFailureShowsErrorMessage() =
        runBlocking {
            val failingRepository =
                object : ConfigRepository(app) {
                    override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> {
                        return Result.failure(RuntimeException("simulated datastore failure"))
                    }
                }

            // Use real IO dispatchers so the launch gets a real suspension point.
            val realDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = failingRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = realIoTestDispatchers(),
                )

            val realViewModel = NetworkPolicyViewModel(realDeps)
            realViewModel.savePreferences(AndroidAppPreferences())

            // Collect snackbar messages with a timeout.
            val messages = mutableListOf<String>()
            val job =
                launch {
                    realDeps.snackbar.messages.collect { messages.add(it) }
                }

            withTimeout(5_000) {
                while (messages.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(10)
                }
            }

            val message = messages.first()
            assertFalse(
                "failure must not show success message",
                message == "Network policy updated",
            )
            assertTrue(
                "failure message must be non-blank",
                message.isNotBlank(),
            )
            job.cancel()
        }

    @Test
    fun savePreferencesFailureDoesNotShowSuccess() =
        runBlocking {
            val failingRepository =
                object : ConfigRepository(app) {
                    override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> {
                        return Result.failure(RuntimeException("no write"))
                    }
                }

            val realDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = failingRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = realIoTestDispatchers(),
                )

            val realViewModel = NetworkPolicyViewModel(realDeps)
            realViewModel.savePreferences(AndroidAppPreferences())

            // Collect snackbar messages with a timeout.
            val messages = mutableListOf<String>()
            val job =
                launch {
                    realDeps.snackbar.messages.collect { messages.add(it) }
                }

            withTimeout(5_000) {
                while (messages.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(10)
                }
            }

            assertFalse(
                "failure must not show 'Network policy updated'",
                messages.any { it == "Network policy updated" },
            )
            job.cancel()
        }

    @Test
    fun networkStatusCombinesPolicyAndPreferences() =
        runBlocking {
            val status =
                kotlinx.coroutines.withTimeout(5_000) {
                    viewModel.networkStatus.first()
                }

            assertTrue(
                "default combined status must allow tunnel (unmetered wifi)",
                status.networkType == NetworkType.UnmeteredWifi,
            )
        }

    @Test
    fun preferencesReflectsRepository() =
        runBlocking {
            val prefs = deps.configRepository.preferences
            val collected =
                runCatching {
                    kotlinx.coroutines.withTimeout(5_000) {
                        prefs.first()
                    }
                }

            assertTrue(
                "default preferences must load",
                collected.isSuccess,
            )
        }
}
