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

    fun current(): List<ForwardConfig> = _forwards.value

    suspend fun refresh() {
        mutex.withLock {
            withContext(dispatchers.io) {
                store.loadForwardsResult()
                    .onSuccess {
                        _forwards.value = it
                        _loadError.value = null
                        hasValidBaseline = true
                    }
                    .onFailure { _loadError.value = describeForwardsFailure(it) }
                // onFailure keeps the existing in-memory list and baseline state.
            }
        }
    }

    suspend fun upsert(forward: ForwardConfig): ValidationResult =
        mutate { current ->
            current.toMutableList().apply {
                val index = indexOfFirst { it.id == forward.id }
                if (index >= 0) set(index, forward) else add(forward)
            }
        }

    suspend fun delete(forwardId: String): ValidationResult =
        mutate { current -> current.filterNot { it.id == forwardId } }

    /** Persist an exact list (used for rollback). Save-then-publish; serialized. */
    suspend fun save(forwards: List<ForwardConfig>): Result<Unit> =
        mutex.withLock {
            withContext(dispatchers.io) {
                runCatching { store.saveForwards(forwards) }.onSuccess { _forwards.value = forwards }
            }
        }

    private suspend fun mutate(transform: (List<ForwardConfig>) -> List<ForwardConfig>): ValidationResult =
        mutex.withLock {
            withContext(dispatchers.io) {
                if (!hasValidBaseline) {
                    return@withContext ValidationResult(
                        false,
                        _loadError.value ?: "Saved forwards file is corrupt; cannot save",
                    )
                }
                val updated = transform(_forwards.value)
                val error = store.validateForwards(updated)
                if (error != null) {
                    return@withContext ValidationResult(false, error)
                }
                runCatching { store.saveForwards(updated) }.fold(
                    onSuccess = {
                        _forwards.value = updated
                        ValidationResult(true, null)
                    },
                    onFailure = { ValidationResult(false, describeForwardsFailure(it)) },
                )
            }
        }
}
