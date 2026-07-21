package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.model.TunnelMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest : AppViewModelTestBase() {
    @Test
    fun homeViewModelStartOfferSendsForegroundServiceIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.startTunnel(TunnelMode.Offer)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, started.action)
        assertEquals(TunnelForegroundService::class.java.name, started.component?.className)
    }

    @Test
    fun homeViewModelStartAnswerSendsForegroundServiceIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.startTunnel(TunnelMode.Answer)
        val started = Shadows.shadowOf(app).nextStartedService
        assertEquals(null, started)
    }

    @Test
    fun homeViewModelStopSendsStopIntent() {
        val viewModel = HomeViewModel(deps)
        viewModel.stopTunnel()
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TunnelForegroundService.ACTION_STOP, started.action)
        assertEquals(TunnelForegroundService::class.java.name, started.component?.className)
    }

    @Test
    fun homeViewModelAllowMeteredTemporarilyDoesNotPersistPreference() =
        runBlocking {
            configRepository.savePreferences(
                com.phillipchin.webrtctunnel.model.AndroidAppPreferences(
                    allowMetered = false,
                    resumeOnUnmetered = true,
                    showMeteredWarning = true,
                    debugLogsEnabled = false,
                    advancedSettingsEnabled = false,
                ),
            ).getOrThrow()
            val viewModel = HomeViewModel(deps)
            viewModel.allowMeteredTemporarily()
            val started = Shadows.shadowOf(app).nextStartedService
            assertNotNull(started)
            assertEquals(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION, started.action)
            assertEquals(false, configRepository.preferences.first().allowMetered)
        }

    @Test
    fun homeViewModelRefreshDelegatesToRepository() {
        val viewModel = HomeViewModel(deps)
        assertSame(tunnelRepository.status, viewModel.status)
        viewModel.refresh()
        assertEquals(1, recordingBridge.statusReads)
    }
}
