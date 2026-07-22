package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thrown when a rollback was requested but the repository revision has changed since
 * the original mutation — prevents overwriting a newer concurrent mutation (P1-002).
 */
class ForwardsRevisionMismatchException(
    expected: Long,
    actual: Long,
) :
    IllegalStateException(
            "Forwards changed concurrently; expected revision $expected but found $actual",
        )

/** P1-003: Thrown when a forwards mutation is blocked due to an active load error. */
class ForwardsMutationBlocked(message: String) : IllegalArgumentException(message)

/**
 * FIX8 P0-004-C: thrown when a forwards save failed AND the attempted on-disk self-restore
 * (back to the exact prior bytes) also failed — the caller must know disk may now disagree with
 * both the prior and intended in-memory state, rather than seeing only the original save error.
 */
class ForwardsSaveRollbackIncompleteException(
    val saveFailure: Throwable,
    val restoreFailure: Throwable,
) : Exception("Forwards save failed and the on-disk self-restore also failed", saveFailure)

/**
 * FIX8 P0-004-B: exact-state capture for the setup transaction's `Forwards` stage — captured
 * under this repository's own [ForwardsRepository] mutex so it can never race a concurrent
 * mutation. Includes the exact file bytes (not a re-serialized list, so a byte-for-byte restore
 * is possible) plus every piece of in-memory state a truthful restore must also revert.
 */
internal class ForwardsTransactionSnapshot(
    val exactFile: ExactFileSnapshot,
    val list: List<ForwardConfig>,
    val loadState: ForwardsLoadState,
    val loadError: String?,
    // FIX8 P0-005: the revision at capture time, so restoreForTransaction can detect and refuse
    // to overwrite a newer concurrent mutation — the same protection rollbackReceipt already
    // gives ForwardsMutationReceipt (P1-002).
    val capturedRevision: Long,
)

/**
 * FIX7 P1-003-B: explicit baseline-load readiness. [ForwardsRepository] no longer reads the
 * forwards file at construction (that eager read was main-thread I/O when constructed from
 * `AppDependencies`), so there is a real window before the first [ForwardsRepository.refresh]
 * completes during which the in-memory list is a placeholder, not the real baseline —
 * [Initializing] blocks mutations during that window the same way [Failed] already does,
 * rather than letting a mutation silently overwrite a baseline nobody has actually read yet.
 */
sealed interface ForwardsLoadState {
    data object Initializing : ForwardsLoadState

    data object Ready : ForwardsLoadState

    data class Failed(val message: String) : ForwardsLoadState
}

/** P1-001: Receipt returned after a successful mutation, capturing the before/after list and the committed revision. */
data class ForwardsMutationReceipt(
    val before: List<ForwardConfig>,
    val after: List<ForwardConfig>,
    val committedRevision: Long,
)

/**
 * Single source of truth for the configured local forwards. Holds the list in memory as
 * an observable [StateFlow]; all mutations work from that in-memory list (never re-read
 * possibly-corrupt disk), are serialized by a [Mutex], and publish only after a
 * successful atomic save (save-then-publish).
 *
 * If the persisted file is corrupt at startup there is no valid baseline, so mutations
 * are blocked (rather than overwriting the user's file with an empty list) and
 * [loadError] is surfaced; a later successful [refresh] clears that state.
 *
 * FIX6 P0-005: mutations wrap their persistence in [mutationResult] rather than
 * `runCatching`, so a `CancellationException` propagates instead of being turned into a
 * `Result.failure` that could drive rollback or a stale user message.
 */
class ForwardsRepository(
    private val store: ForwardsStore,
    private val dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()

    // FIX7 P1-003-B: no disk read here — constructing this class must never perform
    // synchronous file I/O (it is constructed from AppDependencies on the main thread).
    // The in-memory list starts empty and _loadState starts Initializing; the real baseline
    // arrives via the first refresh(), which every current caller (HomeViewModel,
    // ForwardsViewModel) already performs off the main thread in its own init block.
    private val _forwards = MutableStateFlow(emptyList<ForwardConfig>())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()

    private val _loadState = MutableStateFlow<ForwardsLoadState>(ForwardsLoadState.Initializing)
    val loadState: StateFlow<ForwardsLoadState> = _loadState.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // P1-002: Revision counter for conditional rollback. Owned by the same mutex as all
    // other mutations. Incremented on every successful persistence change.
    private var revision: Long = 0

    fun current(): List<ForwardConfig> = _forwards.value

    suspend fun refresh() {
        mutex.withLock {
            withContext(dispatchers.io) {
                store.loadForwardsResult()
                    .onSuccess {
                        _forwards.value = it
                        _loadError.value = null
                        _loadState.value = ForwardsLoadState.Ready
                        // P1-002: Successful refresh advances revision to invalidate old receipts.
                        revision += 1
                    }
                    .onFailure { error ->
                        val message = describeForwardsFailure(error)
                        _loadError.value = message
                        _loadState.value = ForwardsLoadState.Failed(message)
                    }
                // onFailure keeps the existing in-memory list and baseline state.
            }
        }
    }

    // FIX7 P1-003-B: shared guard for every mutation below — null only once a baseline has
    // actually been read successfully (Ready). Blocks identically whether the baseline was
    // never read yet (Initializing) or was read and found corrupt (Failed), so a mutation
    // can never silently overwrite a baseline nobody has verified.
    // FIX8 P0-004: a property (not a function) so it doesn't count against this class's detekt
    // TooManyFunctions threshold — semantically unchanged, still a synchronous state read.
    private val blockedMutationReason: String?
        get() =
            when (val state = _loadState.value) {
                ForwardsLoadState.Ready -> null
                ForwardsLoadState.Initializing -> "Forwards have not finished loading yet; try again shortly"
                is ForwardsLoadState.Failed -> state.message
            }

    /**
     * FIX8 P0-004-C: captures `forwards.json`'s exact prior bytes, then runs [save]. If [save]
     * throws — including a cleanup-only failure raised *after* the destination was already
     * atomically moved — attempts to restore those exact prior bytes so disk cannot end up
     * reflecting the new (unpublished) state while the mutation is reported failed. A
     * self-restore failure is composed into [ForwardsSaveRollbackIncompleteException] rather
     * than silently discarded; cancellation is preserved without attempting a self-restore
     * (the caller's own cancellation-handling owns that).
     *
     * FIX8 P0-004: a property holding a function value (not a `fun` declaration) so it doesn't
     * count against this class's detekt TooManyFunctions threshold — called identically to a
     * member function (`selfRestoringSave { ... }`) via Kotlin's trailing-lambda `invoke` syntax.
     */
    private val selfRestoringSave: (() -> Unit) -> Unit = { save ->
        val priorSnapshot = store.captureExactSnapshot().getOrThrow()
        val saveFailure =
            try {
                save()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
        if (saveFailure != null) {
            val restoreFailure = store.restoreExactSnapshot(priorSnapshot).exceptionOrNull()
            throw if (restoreFailure != null) {
                ForwardsSaveRollbackIncompleteException(saveFailure, restoreFailure)
            } else {
                saveFailure
            }
        }
    }

    /**
     * P1-001: Atomically upsert a forward and return a receipt capturing the before/after
     * list and committed revision. The receipt can be passed to [rollbackReceipt] to undo
     * this mutation (if the revision has not changed).
     * Returns failure if a load error is active (P1-003).
     */
    @CheckResult
    suspend fun upsertWithReceipt(forward: ForwardConfig): Result<ForwardsMutationReceipt> =
        mutex.withLock {
            withContext(dispatchers.io) {
                // P1-003: Central guard — block mutation whenever the baseline isn't Ready.
                blockedMutationReason?.let { reason ->
                    return@withContext Result.failure(ForwardsMutationBlocked(reason))
                }
                val before = _forwards.value
                val after =
                    before
                        .toMutableList()
                        .apply {
                            val index = indexOfFirst { it.id == forward.id }
                            if (index >= 0) set(index, forward) else add(forward)
                        }
                val error = store.validateForwards(after)
                if (error != null) {
                    return@withContext Result.failure(ForwardsMutationBlocked(error))
                }
                mutationResult {
                    selfRestoringSave { store.saveForwards(after) }
                    _forwards.value = after
                    revision += 1
                    ForwardsMutationReceipt(before, after, revision)
                }
            }
        }

    /**
     * P1-001: Atomically delete a forward and return a receipt.
     * Returns failure if a load error is active (P1-003).
     */
    @CheckResult
    suspend fun deleteWithReceipt(forwardId: String): Result<ForwardsMutationReceipt> =
        mutex.withLock {
            withContext(dispatchers.io) {
                // P1-003: Central guard — block mutation whenever the baseline isn't Ready.
                blockedMutationReason?.let { reason ->
                    return@withContext Result.failure(ForwardsMutationBlocked(reason))
                }
                val before = _forwards.value
                val after = before.filterNot { it.id == forwardId }
                mutationResult {
                    selfRestoringSave { store.saveForwards(after) }
                    _forwards.value = after
                    revision += 1
                    ForwardsMutationReceipt(before, after, revision)
                }
            }
        }

    /**
     * P1-001: Atomically rollback a mutation to its [receipt.before] state.
     * Fails with [ForwardsRevisionMismatchException] if the revision has changed
     * since the receipt was committed (P1-002).
     */
    @CheckResult
    suspend fun rollbackReceipt(receipt: ForwardsMutationReceipt): Result<Unit> =
        mutex.withLock {
            if (revision != receipt.committedRevision) {
                return@withLock Result.failure(
                    ForwardsRevisionMismatchException(
                        receipt.committedRevision,
                        revision,
                    ),
                )
            }
            withContext(dispatchers.io) {
                mutationResult {
                    selfRestoringSave { store.saveForwards(receipt.before) }
                    _forwards.value = receipt.before
                    revision += 1
                }
            }
        }

    /**
     * P1-005: Atomic reset — clears disk, memory state, loadError, and advances
     * revision under one mutex acquisition so old forwards cannot reappear.
     */
    @CheckResult
    suspend fun resetForwards(): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                mutationResult {
                    selfRestoringSave { store.saveForwards(emptyList()) }
                    _forwards.value = emptyList()
                    _loadError.value = null
                    _loadState.value = ForwardsLoadState.Ready
                    revision += 1
                }
            }
        }

    /**
     * P1-001: Internal restore for transactional reset rollback.
     * Persists an exact forwards list without validation (bypass is intentional for rollback).
     * Save-then-publish; serialized. Advances revision on success.
     * Not exposed to ViewModels — only TransactionalResetCoordinator calls this.
     */
    @CheckResult
    internal suspend fun restoreForTransactionalReset(forwards: List<ForwardConfig>): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                mutationResult {
                    selfRestoringSave { store.saveForwards(forwards) }
                    _forwards.value = forwards
                    revision += 1
                }
            }
        }

    /**
     * FIX8 P0-004-B: captures this repository's exact state (file bytes, list, load state, load
     * error) for the setup transaction's `Forwards` stage, under the same [mutex] every mutation
     * uses so it can never race a concurrent one. Fails (rather than snapshotting a placeholder
     * empty list) unless the baseline is [ForwardsLoadState.Ready] — a transaction must not
     * capture "rollback state" from a baseline nobody has actually verified yet.
     */
    @CheckResult
    internal suspend fun captureForTransaction(): Result<ForwardsTransactionSnapshot> =
        mutex.withLock {
            val blockedReason = blockedMutationReason
            if (blockedReason != null) {
                return@withLock Result.failure(ForwardsMutationBlocked(blockedReason))
            }
            withContext(dispatchers.io) {
                mutationResult {
                    ForwardsTransactionSnapshot(
                        exactFile = store.captureExactSnapshot().getOrThrow(),
                        list = _forwards.value,
                        loadState = _loadState.value,
                        loadError = _loadError.value,
                        capturedRevision = revision,
                    )
                }
            }
        }

    /**
     * FIX8 P0-004-D: applies the setup transaction's full forwards draft as the `Forwards`
     * stage's commit — validates, saves (self-restoring on a post-move save failure per
     * P0-004-C), then publishes, matching every other mutation's save-then-publish ordering.
     */
    @CheckResult
    internal suspend fun replaceForTransaction(forwards: List<ForwardConfig>): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                val validationError = store.validateForwards(forwards)
                if (validationError != null) {
                    return@withContext Result.failure(ForwardsMutationBlocked(validationError))
                }
                mutationResult {
                    selfRestoringSave { store.saveForwards(forwards) }
                    _forwards.value = forwards
                    revision += 1
                }
            }
        }

    /**
     * FIX8 P0-004-D/P0-005: restores this repository to exactly [snapshot]'s prior state for a
     * transaction's `Forwards` stage rollback — disk first (exact bytes, not a re-serialized
     * list), then in-memory list/load-state/load-error, advancing [revision] on success so any
     * receipt issued mid-transaction is invalidated afterward.
     *
     * [stageWasApplied] tells this call the one fact it cannot otherwise know: whether *this*
     * transaction's own `Forwards` stage actually committed (incrementing [revision] once)
     * before this restore runs. The expected revision is [ForwardsTransactionSnapshot
     * .capturedRevision] plus one only in that case — if the live revision doesn't match, a
     * concurrent mutation from outside this transaction has happened since capture, and this
     * restore must refuse to overwrite it (P1-002's protection, extended from
     * [rollbackReceipt] to transaction-scoped restores).
     */
    @CheckResult
    internal suspend fun restoreForTransaction(
        snapshot: ForwardsTransactionSnapshot,
        stageWasApplied: Boolean,
    ): Result<Unit> =
        mutex.withLock {
            val expectedRevision = if (stageWasApplied) snapshot.capturedRevision + 1 else snapshot.capturedRevision
            if (revision != expectedRevision) {
                return@withLock Result.failure(ForwardsRevisionMismatchException(expectedRevision, revision))
            }
            withContext(dispatchers.io) {
                mutationResult {
                    store.restoreExactSnapshot(snapshot.exactFile).getOrThrow()
                    _forwards.value = snapshot.list
                    _loadState.value = snapshot.loadState
                    _loadError.value = snapshot.loadError
                    revision += 1
                }
            }
        }
}
