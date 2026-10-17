package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The coordinator snapshots the prior broker secret so it can restore it on rollback. Those
 * snapshot bytes are a credential, so they must be zeroed on every exit path: success, ordinary
 * failure and cancellation.
 */
@RunWith(RobolectricTestRunner::class)
internal class SetupPersistenceCoordinatorSecretWipingTest : SetupPersistenceCoordinatorTestBase() {
    @Test
    fun brokerSecretSnapshotBytesAreWipedAfterSuccess() =
        runBlocking {
            brokerSecretRepository.persist("prior-secret-success").getOrThrow()
            val tracked = "prior-secret-success".toByteArray()
            val tracking = brokerSecretRepositoryReadingTracked(context, tracked)

            val result =
                coordinator(RecordingPreferences(), brokerSecret = tracking).persist(
                    request(brokerSecretChange = BrokerSecretChange.Set("new-secret-success")),
                )

            assertTrue(result is SetupPersistenceResult.Success)
            assertTrue("success: broker secret snapshot bytes must be wiped", tracked.all { it == 0.toByte() })
        }

    @Test
    fun brokerSecretSnapshotBytesAreWipedAfterFailure() =
        runBlocking {
            brokerSecretRepository.persist("prior-secret-failure").getOrThrow()
            val tracked = "prior-secret-failure".toByteArray()
            val tracking = brokerSecretRepositoryReadingTracked(context, tracked)

            val result =
                coordinator(RecordingPreferences(), config = FailingConfig(context), brokerSecret = tracking)
                    .persist(request(brokerSecretChange = BrokerSecretChange.Set("new-secret-failure")))

            assertTrue(result is SetupPersistenceResult.Failed)
            assertTrue("failure: broker secret snapshot bytes must be wiped", tracked.all { it == 0.toByte() })
        }

    @Test
    fun brokerSecretSnapshotBytesAreWipedAfterCancellation() =
        runBlocking {
            brokerSecretRepository.persist("prior-secret-cancel").getOrThrow()
            val tracked = "prior-secret-cancel".toByteArray()
            val tracking = brokerSecretRepositoryReadingTracked(context, tracked)

            persistExpectingCancellation(
                coordinator(RecordingPreferences(), config = CancellingConfig(context), brokerSecret = tracking),
                during = "Config",
                request = request(brokerSecretChange = BrokerSecretChange.Set("new-secret-cancel")),
            )

            assertTrue("cancellation: broker secret snapshot bytes must be wiped", tracked.all { it == 0.toByte() })
        }
}
