package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
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

/** Snapshot of forwards with the repository revision at the time of the snapshot. */
data class ForwardsSnapshot(
    val forwards: List<ForwardConfig>,
    val revision: Long,
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

    /** Returns the current snapshot with the latest revision. */
    suspend fun snapshot(): ForwardsSnapshot =
        mutex.withLock {
            ForwardsSnapshot(_forwards.value, revision)
        }

    /**
     * Saves forwards only if [expectedRevision] matches the current revision.
     * Returns failure with [ForwardsRevisionMismatchException] if the revision
     * has changed, preventing rollback from overwriting newer data (P1-002).
     */
    suspend fun saveIfRevisionMatches(
        expectedRevision: Long,
        forwards: List<ForwardConfig>,
    ): Result<Unit> =
        mutex.withLock {
            if (revision != expectedRevision) {
                return@withLock Result.failure(
                    ForwardsRevisionMismatchException(expectedRevision, revision),
                )
            }
            withContext(dispatchers.io) {
                runCatching {
                    store.saveForwards(forwards)
                    _forwards.value = forwards
                    revision += 1
                }
            }
        }

    /**
     * Result of a forwards mutation operation, including the ValidationResult and
     * the revision after the mutation (for rollback targeting) (P1-002).
     */
    data class MutationResult(
        val validationResult: ValidationResult,
        val revision: Long,
    )

    suspend fun upsert(forward: ForwardConfig): MutationResult =
        mutate { current ->
            current.toMutableList().apply {
                val index = indexOfFirst { it.id == forward.id }
                if (index >= 0) set(index, forward) else add(forward)
            }
        }

    suspend fun delete(forwardId: String): MutationResult =
        mutate { current -> current.filterNot { it.id == forwardId } }

    /** Persist an exact list (used for rollback). Save-then-publish; serialized. Advances revision on success. */
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

    /**
     * P1-005: Atomic reset — clears disk, in-memory state, loadError, and advances
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

    private suspend fun mutate(transform: (List<ForwardConfig>) -> List<ForwardConfig>): MutationResult =
        mutex.withLock {
            withContext(dispatchers.io) {
                // P1-003: Central guard — block mutation whenever loadError is active.
                if (_loadError.value != null) {
                    return@withContext MutationResult(
                        ValidationResult(
                            false,
                            _loadError.value ?: "Saved forwards file is corrupt; cannot save",
                        ),
                        revision,
                    )
                }
                val updated = transform(_forwards.value)
                val error = store.validateForwards(updated)
                if (error != null) {
                    return@withContext MutationResult(ValidationResult(false, error), revision)
                }
                runCatching { store.saveForwards(updated) }.fold(
                    onSuccess = {
                        _forwards.value = updated
                        revision += 1
                        MutationResult(ValidationResult(true, null), revision)
                    },
                    onFailure = {
                        MutationResult(ValidationResult(false, describeForwardsFailure(it)), revision)
                    },
                )
            }
        }
}
