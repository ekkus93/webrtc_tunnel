package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Before
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

// Shared fixtures for the SetupPersistenceCoordinator*Test classes in this package: the common
// real-repository wiring ([SetupPersistenceCoordinatorTestBase]) and the fault-injecting fakes
// that drive each stage to fail or be cancelled deterministically. `internal` (not `private`) so
// every file in this package can reach them. Split out of SetupPersistenceCoordinatorTest, which
// had grown past 800 lines.

/**
 * Base for the coordinator tests: wipes the app's files, then builds real repositories over them
 * so every test starts from the same blank slate. Subclasses pick the fault-injecting fake they
 * need per test and hand it to [coordinator]. Deliberately carries no `@RunWith`: each concrete
 * subclass declares the Robolectric runner itself, so Gradle does not try to run this base as a
 * test class of its own.
 */
internal open class SetupPersistenceCoordinatorTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var configRepository: ConfigRepository
    protected lateinit var identityRepository: IdentityRepository
    protected lateinit var brokerSecretRepository: BrokerSecretRepository
    protected lateinit var forwardsRepository: ForwardsRepository
    protected val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    @Before
    fun setUp() =
        runBlocking {
            listOf("config.toml", "setup_input.json", "identity.enc", "identity.pub", "authorized_keys").forEach {
                File(context.filesDir, it).delete()
            }
            File(context.filesDir, "runtime").deleteRecursively()
            File(context.filesDir, "forwards.json").delete()
            configRepository = ConfigRepository(context)
            identityRepository = IdentityRepository(context, PlaintextCrypto())
            brokerSecretRepository = BrokerSecretRepository(context, permissionEnforcer = RecordingPermissionEnforcer())
            forwardsRepository = ForwardsRepository(ForwardsConfigStore(context), AppDispatchers())
            // The Forwards stage's captureForTransaction() fails unless the baseline is Ready.
            forwardsRepository.refresh()
        }

    protected fun coordinator(
        prefs: RecordingPreferences,
        config: ConfigRepository = configRepository,
        identity: IdentityRepository = identityRepository,
        brokerSecret: BrokerSecretRepository = brokerSecretRepository,
        forwards: ForwardsRepository = forwardsRepository,
    ) = SetupPersistenceCoordinator(config, identity, brokerSecret, forwards, prefs.load, prefs.persist)

    // Forwards-specific requests (non-empty draft) are covered by
    // SetupPersistenceCoordinatorForwardsTest — every request here uses an empty draft.
    protected fun request(
        replacementIdentity: IdentityReplacement? = null,
        authorizedPublicIdentityToAdd: String? = null,
        brokerSecretChange: BrokerSecretChange? = null,
        configContents: String = "format = \"committed\"\n",
        setupInput: SetupConfigInput = SetupConfigInput(brokerHost = "broker.new"),
    ) = SetupPersistenceRequest(
        configContents,
        setupInput,
        AndroidAppPreferences(resumeOnUnmetered = false),
        emptyList(),
        SetupOptionalChanges(replacementIdentity, authorizedPublicIdentityToAdd, brokerSecretChange),
    )

    protected fun fullRequest() =
        request(
            replacementIdentity = IdentityReplacement("new-priv".toByteArray(), "new-pub"),
            authorizedPublicIdentityToAdd = "remote-key peer",
            brokerSecretChange = BrokerSecretChange.Set("new-broker-secret"),
        )

    /**
     * Runs [SetupPersistenceCoordinator.persist] and returns the [CancellationException] it must
     * propagate. Fails the test if persist instead returns a result. [during] names the stage that
     * is being cancelled, for the failure message.
     */
    protected suspend fun persistExpectingCancellation(
        coordinator: SetupPersistenceCoordinator,
        during: String,
        request: SetupPersistenceRequest = fullRequest(),
    ): CancellationException {
        try {
            val outcome = coordinator.persist(request)
            throw AssertionError("cancellation during $during must propagate, but persist returned $outcome")
        } catch (cancelled: CancellationException) {
            return cancelled
        }
    }
}

internal fun SetupRollbackStageResult.stage(): SetupPersistenceStage =
    when (this) {
        is SetupRollbackStageResult.Success -> stage
        is SetupRollbackStageResult.Failure -> stage
    }

// --- Identity crypto fakes ------------------------------------------------------------------

/** Stores plaintext verbatim so tests can read back exactly what was written. */
internal class PlaintextCrypto : IdentityCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

    override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
}

/** Throws on encrypt so the Identity stage fails deterministically. */
internal class ThrowingCrypto : IdentityCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = throw IOException("identity write failed")

    override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
}

/** Throws cancellation on encrypt, to drive "cancelled during the very first stage". */
internal class CancellingCrypto : IdentityCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = throw CancellationException("identity write cancelled")

    override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf()
}

// --- ConfigRepository fakes -----------------------------------------------------------------

/** ConfigRepository whose config write always fails (drives Config-stage failure). */
internal class FailingConfig(context: Context) : ConfigRepository(context) {
    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        Result.failure(IOException("config commit failed"))
}

/** ConfigRepository whose config write always throws cancellation (drives Config-stage
 * cancellation while every earlier stage has already committed). */
internal class CancellingConfig(context: Context) : ConfigRepository(context) {
    override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        throw CancellationException("config write cancelled")
}

/** ConfigRepository whose setup-input write always fails (drives SetupInput-stage failure).
 * FIX8 P0-003-B: the coordinator's SetupInput stage now calls saveSetupInputAtomically
 * (atomic, Result-returning), not saveSetupInput — override that one, or this fake goes
 * silently inert and the real atomic write succeeds instead of failing as the test expects. */
internal class FailingSetupInput(context: Context) : ConfigRepository(context) {
    override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
        Result.failure(IOException("setup input write failed"))
}

/** ConfigRepository whose setup-input write always throws cancellation (drives SetupInput-
 * stage cancellation while Identity/AuthorizedKeys/BrokerSecret have already committed). */
internal class CancellingSetupInput(context: Context) : ConfigRepository(context) {
    override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
        throw CancellationException("setup input write cancelled")
}

/** ConfigRepository whose snapshot capture (of the *current* config+setup-input, taken before
 * any mutation) always fails, driving a Snapshot-stage abort before the first mutation.
 * FIX8 P0-003-E: captureFilesSnapshot() reads the files directly rather than going through
 * configContents, so the fake must override captureFilesSnapshot() itself. */
internal class FailingSnapshotConfig(context: Context) : ConfigRepository(context) {
    override suspend fun captureFilesSnapshot(): Result<ConfigFilesSnapshot> =
        Result.failure(IOException("snapshot read failed"))
}

/**
 * Records the maximum number of overlapping saveSetupInput calls to prove serialization.
 * FIX7 P2-001-A: the first call blocks on a test-controlled [releaseFirst] barrier (never an
 * elapsed-time guess) so the second call has an unbounded window to attempt entry while the
 * first is still inside — a broken mutex would let it in, a working one cannot.
 */
internal class ConcurrencyProbe(context: Context) : ConfigRepository(context) {
    val maxConcurrent = AtomicInteger(0)
    private val active = AtomicInteger(0)
    val firstEntered = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    // FIX8 P0-003-B: the coordinator's SetupInput stage now calls saveSetupInputAtomically
    // (atomic, Result-returning) instead of saveSetupInput — the probe must gate the method
    // actually invoked, or firstEntered never completes and the test deadlocks forever.
    override suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> {
        val now = active.incrementAndGet()
        maxConcurrent.updateAndGet { existing -> maxOf(existing, now) }
        if (!firstEntered.isCompleted) {
            firstEntered.complete(Unit)
            releaseFirst.await()
        }
        active.decrementAndGet()
        return super.saveSetupInputAtomically(input)
    }
}

// --- Preferences fake -----------------------------------------------------------------------

internal class RecordingPreferences {
    var stored: AndroidAppPreferences = AndroidAppPreferences()
    val writes = mutableListOf<AndroidAppPreferences>()
    var failOnWriteNumber: Int = -1
    var cancelOnWriteNumber: Int = -1
    private val writeCount = AtomicInteger(0)

    val load: suspend () -> AndroidAppPreferences = { stored }
    val persist: suspend (AndroidAppPreferences) -> Result<Unit> = { prefs ->
        when (writeCount.incrementAndGet()) {
            cancelOnWriteNumber -> throw CancellationException("cancelled during preference write")
            failOnWriteNumber -> Result.failure(IOException("preference write failed"))
            else -> {
                writes.add(prefs)
                stored = prefs
                Result.success(Unit)
            }
        }
    }
}

// --- Repository fakes -----------------------------------------------------------------------

/** [IdentityRepository] whose `authorized_keys` write always throws cancellation, while its
 * identity-pair write (same injected [atomicReplace] seam) still succeeds normally — drives
 * "cancelled during AuthorizedKeys, after Identity already committed". */
internal fun identityRepositoryCancellingAuthorizedKeysWrite(context: Context): IdentityRepository =
    IdentityRepository(
        context,
        PlaintextCrypto(),
        atomicReplace = { file, bytes ->
            if (file.name == "authorized_keys") {
                throw CancellationException("authorized_keys write cancelled")
            }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        },
    )

/** [BrokerSecretRepository] whose replacement write always throws cancellation. */
internal fun brokerSecretRepositoryCancellingReplace(context: Context): BrokerSecretRepository =
    BrokerSecretRepository(
        context,
        atomicReplace = { _, _ -> throw CancellationException("broker secret write cancelled") },
    )

/** [BrokerSecretRepository] whose replacement write fails deterministically on its [failOn]-th
 * call (1-indexed) and otherwise performs a plain (non-atomic — precision isn't the point of
 * this fake) write; used to make the Nth call — e.g. a rollback restore rather than the
 * initial apply — fail while an earlier call of the same kind succeeds. */
internal fun brokerSecretRepositoryFailingOnNthReplace(
    context: Context,
    failOn: Int,
): BrokerSecretRepository {
    val callCount = AtomicInteger(0)
    return BrokerSecretRepository(
        context,
        atomicReplace = { file, bytes ->
            if (callCount.incrementAndGet() == failOn) {
                error("simulated broker secret failure #$failOn")
            }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        },
    )
}

/** [BrokerSecretRepository] whose snapshot read returns [tracked] itself, so a test can later
 * assert the coordinator wiped exactly the bytes it was handed. */
internal fun brokerSecretRepositoryReadingTracked(
    context: Context,
    tracked: ByteArray,
): BrokerSecretRepository =
    BrokerSecretRepository(
        context,
        readBytes = { tracked },
        permissionEnforcer = RecordingPermissionEnforcer(),
    )
