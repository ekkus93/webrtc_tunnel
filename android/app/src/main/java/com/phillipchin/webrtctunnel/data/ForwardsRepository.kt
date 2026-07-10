package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
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
 */
class ForwardsRepository(
    private val store: ForwardsConfigStore,
    private val dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()

    private val initial = store.loadForwardsResult()
    private val _forwards = MutableStateFlow(initial.getOrDefault(emptyList()))
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()

    private val _loadError =
        MutableStateFlow(initial.exceptionOrNull()?.let { describeForwardsFailure(it) })
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var hasValidBaseline = initial.isSuccess

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
                        hasValidBaseline = true
                        // P1-002: Successful refresh advances revision to invalidate old receipts.
                        revision += 1
                    }
                    .onFailure { _loadError.value = describeForwardsFailure(it) }
                // onFailure keeps the existing in-memory list and baseline state.
            }
        }
    }

    /**
     * P1-001: Atomically upsert a forward and return a receipt capturing the before/after
     * list and committed revision. The receipt can be passed to [rollbackReceipt] to undo
     * this mutation (if the revision has not changed).
     * Returns failure if a load error is active (P1-003).
     */
    suspend fun upsertWithReceipt(forward: ForwardConfig): Result<ForwardsMutationReceipt> =
        mutex.withLock {
            withContext(dispatchers.io) {
                // P1-003: Central guard — block mutation whenever loadError is active.
                if (_loadError.value != null) {
                    return@withContext Result.failure(
                        ForwardsMutationBlocked(
                            _loadError.value ?: "Saved forwards file is corrupt; cannot mutate",
                        ),
                    )
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
                runCatching {
                    store.saveForwards(after)
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
    suspend fun deleteWithReceipt(forwardId: String): Result<ForwardsMutationReceipt> =
        mutex.withLock {
            withContext(dispatchers.io) {
                // P1-003: Central guard — block mutation whenever loadError is active.
                if (_loadError.value != null) {
                    return@withContext Result.failure(
                        ForwardsMutationBlocked(
                            _loadError.value ?: "Saved forwards file is corrupt; cannot mutate",
                        ),
                    )
                }
                val before = _forwards.value
                val after = before.filterNot { it.id == forwardId }
                runCatching {
                    store.saveForwards(after)
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
                runCatching {
                    store.saveForwards(receipt.before)
                    _forwards.value = receipt.before
                    revision += 1
                }
            }
        }

    /**
     * P1-005: Atomic reset — clears disk, memory state, loadError, and advances
     * revision under one mutex acquisition so old forwards cannot reappear.
     */
    suspend fun resetForwards(): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                runCatching {
                    store.saveForwards(emptyList())
                    _forwards.value = emptyList()
                    _loadError.value = null
                    hasValidBaseline = true
                    revision += 1
                }
            }
        }

    /** Persist an exact list (used for reset/rollback). Save-then-publish; serialized. Advances revision on success. */
    suspend fun save(forwards: List<ForwardConfig>): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                runCatching { store.saveForwards(forwards) }
                    .onSuccess {
                        _forwards.value = forwards
                        revision += 1
                    }
            }
        }
}
