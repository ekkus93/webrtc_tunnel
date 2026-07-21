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

    // FIX7 P1-004-A: a required preference-save failure must survive in ViewModel state, not
    // only in a one-shot snackbar — a missing/late collector must not lose it.
    @Test
    fun networkPreferenceFailureRemainsInStateWithoutSnackbarCollector() =
        runBlocking {
            val failingRepository =
                object : ConfigRepository(app) {
                    override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
                        Result.failure(RuntimeException("disk full"))
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

            // No snackbar collector subscribed at all.
            testViewModel.savePreferences(AndroidAppPreferences())
            withTimeout(5_000) {
                while (testViewModel.uiState.value.lastOperationFailure == null) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            val failure = testViewModel.uiState.value.lastOperationFailure
            assertEquals("network_preference_save_failed", failure?.code)
        }

    @Test
    fun networkPreferenceSuccessClearsPriorFailure() =
        runBlocking {
            val toggle = java.util.concurrent.atomic.AtomicBoolean(true)
            val flakyRepository =
                object : ConfigRepository(app) {
                    override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
                        if (toggle.getAndSet(false)) {
                            Result.failure(RuntimeException("disk full"))
                        } else {
                            super.savePreferences(update)
                        }
                }
            val testDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = flakyRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = inlineTestDispatchers(),
                )
            val testViewModel = NetworkPolicyViewModel(testDeps)

            testViewModel.savePreferences(AndroidAppPreferences())
            withTimeout(5_000) {
                while (testViewModel.uiState.value.lastOperationFailure == null) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            testViewModel.savePreferences(AndroidAppPreferences())
            withTimeout(5_000) {
                while (testViewModel.uiState.value.lastOperationFailure != null) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            assertEquals(null, testViewModel.uiState.value.lastOperationFailure)
        }

    @Test
    fun networkPolicyFailureMessageRedactsPasswordTokenApiKeyAndPrivateKey() =
        runBlocking {
            val failingRepository =
                object : ConfigRepository(app) {
                    override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
                        Result.failure(
                            RuntimeException(
                                "write failed password=hunter2 token=abc123 api_key=xyz789 private_key=zzz",
                            ),
                        )
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

            testViewModel.savePreferences(AndroidAppPreferences())
            withTimeout(5_000) {
                while (testViewModel.uiState.value.lastOperationFailure == null) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yield()
                }
            }

            val message = testViewModel.uiState.value.lastOperationFailure?.message.orEmpty()
            assertFalse(message.contains("hunter2"))
            assertFalse(message.contains("abc123"))
            assertFalse(message.contains("xyz789"))
            assertFalse(message.contains("zzz"))
            assertTrue(message.contains("***REDACTED***"))
        }

    // FIX7 P1-004-B: an evaluateWithPolicy exception must never propagate out of the combine
    // lambda and terminate the networkStatus flow — it must fail closed instead.
    @Test
    fun networkStatusEvaluationFailureEmitsBlockedUnknownAndFlowContinues() {
        val status = evaluateNetworkPolicySafely { error("classification boom") }

        assertEquals(NetworkType.Unknown, status.networkType)
        assertFalse(status.tunnelAllowed)
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
