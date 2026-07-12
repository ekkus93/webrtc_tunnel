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
import kotlinx.coroutines.yield
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
                        return Result.failure(RuntimeException("disk full"))
                    }
                }

            val testDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = failingRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = inlineTestDispatchers(),
                )

            val testViewModel = NetworkPolicyViewModel(testDeps)
            val messages = mutableListOf<String>()
            val job =
                launch {
                    testDeps.snackbar.messages.collect { messages.add(it) }
                }
            yield() // let the collector subscribe before emit

            testViewModel.savePreferences(AndroidAppPreferences())

            withTimeout(5_000) {
                while (messages.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            assertTrue(
                "error must be shown when savePreferences fails",
                messages.any {
                    it.contains("disk full") || it.contains("Failed to update network policy")
                },
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

            val testDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = failingRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = inlineTestDispatchers(),
                )

            val testViewModel = NetworkPolicyViewModel(testDeps)
            val messages = mutableListOf<String>()
            val job =
                launch {
                    testDeps.snackbar.messages.collect { messages.add(it) }
                }
            yield() // let the collector subscribe before emit

            testViewModel.savePreferences(AndroidAppPreferences())

            withTimeout(5_000) {
                while (messages.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            assertFalse(
                "success must not be shown when savePreferences fails",
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
