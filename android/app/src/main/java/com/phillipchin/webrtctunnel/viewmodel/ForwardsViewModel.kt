package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val LOCAL_PORT_TEST_TIMEOUT_MS = 1200

class ForwardsViewModel(
    private val deps: AppDependencies,
    private val ioDispatcher: CoroutineDispatcher = deps.dispatchers.io,
) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    private val _forwards = MutableStateFlow(deps.forwardsStore.loadForwards())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            _forwards.value = withContext(ioDispatcher) { deps.forwardsStore.loadForwards() }
        }
    }

    fun saveForward(forward: ForwardConfig) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val message =
                withContext(ioDispatcher) {
                    val before = deps.forwardsStore.loadForwards()
                    val result = deps.forwardsStore.upsertForward(forward)
                    if (!result.valid) {
                        result.message ?: "Forward update failed"
                    } else {
                        val sync = regenerateActiveConfig()
                        if (!sync.valid) {
                            deps.forwardsStore.saveForwards(before)
                            sync.message ?: "Forward update failed"
                        } else {
                            "Forward saved"
                        }
                    }
                }
            _forwards.value = withContext(ioDispatcher) { deps.forwardsStore.loadForwards() }
            _message.value = message
            _isBusy.value = false
        }
    }

    fun deleteForward(forwardId: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val message =
                withContext(ioDispatcher) {
                    val before = deps.forwardsStore.loadForwards()
                    deps.forwardsStore.deleteForward(forwardId)
                    val sync = regenerateActiveConfig()
                    if (!sync.valid) {
                        deps.forwardsStore.saveForwards(before)
                        sync.message ?: "Forward delete failed"
                    } else {
                        "Forward deleted"
                    }
                }
            _forwards.value = withContext(ioDispatcher) { deps.forwardsStore.loadForwards() }
            _message.value = message
            _isBusy.value = false
        }
    }

    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? {
        val updated =
            currentForwards.map { if (it.id == draft.id) draft else it }.let { candidates ->
                if (candidates.none { it.id == draft.id }) candidates + draft else candidates
            }
        return deps.forwardsStore.validateForwards(updated)
    }

    fun testLocalPort(forward: ForwardConfig) {
        viewModelScope.launch(ioDispatcher) {
            // Connect to the configured local host (blank falls back to loopback),
            // and report the host actually tested rather than a hardcoded address.
            val host = forward.localHost.trim().ifBlank { "127.0.0.1" }
            val resultMessage =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, forward.localPort), LOCAL_PORT_TEST_TIMEOUT_MS)
                    }
                    "Local port test succeeded for $host:${forward.localPort}"
                }.getOrElse {
                    "Local port test failed for $host:${forward.localPort}: ${it.message}"
                }
            _message.value = SensitiveDataRedactor.redactText(resultMessage)
        }
    }

    private fun regenerateActiveConfig(): ValidationResult {
        val input = deps.configRepository.loadSetupInput()
        val forwards = deps.forwardsStore.loadForwards().filter { it.enabled }
        val candidate = deps.configRepository.renderOfferConfig(input, forwards)
        val temp = File(deps.context.cacheDir, "config-forwards-candidate.toml")
        val identity = runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }.getOrNull()
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            val result =
                if (identity != null && identity.isNotEmpty()) {
                    deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identity)
                } else {
                    deps.identityValidation.validateConfig(temp.absolutePath)
                }
            if (result.valid) {
                deps.configRepository.writeConfigAtomically(candidate)
            }
            result
        }.getOrElse { ValidationResult(false, it.message) }.also {
            temp.delete()
        }
    }
}
