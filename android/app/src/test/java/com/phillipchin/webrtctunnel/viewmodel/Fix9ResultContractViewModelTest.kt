package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Fix9ResultContractViewModelTest : AppViewModelTestBase() {
    private fun dependencies(repository: ConfigRepository): AppDependencies =
        AppDependencies(
            context = app,
            nativeBridgeFactory = { recordingBridge },
            configRepository = repository,
            networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
            identityRepository = deps.identityRepository,
            dispatchers = inlineTestDispatchers(),
        )

    @Test
    fun settingsPublishesPreferencesSaveFailedForThrownRepositoryWriterException() {
        val repository =
            ConfigRepository(
                app,
                preferenceWriter = { throw SecurityException("settings write denied") },
            )
        val viewModel = SettingsViewModel(dependencies(repository))

        viewModel.savePreferences(AndroidAppPreferences())
        awaitCondition(description = "settings preference failure") {
            viewModel.uiState.value.lastOperationFailure != null
        }

        assertEquals("preferences_save_failed", viewModel.uiState.value.lastOperationFailure?.code)
    }

    @Test
    fun networkPolicyPublishesFailureForThrownRepositoryWriterException() {
        val repository =
            ConfigRepository(
                app,
                preferenceWriter = { throw IllegalArgumentException("network write rejected") },
            )
        val viewModel = NetworkPolicyViewModel(dependencies(repository))

        viewModel.savePreferences(AndroidAppPreferences())
        awaitCondition(description = "network preference failure") {
            viewModel.uiState.value.lastOperationFailure != null
        }

        assertEquals("network_preference_save_failed", viewModel.uiState.value.lastOperationFailure?.code)
    }
}
