package com.imayx.host.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.graph
import com.imayx.host.pentagi.PentagiHealth
import com.imayx.host.pentagi.PentagiLlmProvider
import com.imayx.host.pentagi.PentagiValidator
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PentagiConfigUi(
    val enabled: Boolean = false,
    val managerUrl: String = "",
    val remoteUrl: String = "",
    val publicUrl: String = "",
    val provider: String = "openai",
    val hasManagerToken: Boolean = false,
    val hasLlmKey: Boolean = false,
    val hasApiToken: Boolean = false,
    val projectEntryExists: Boolean = false
)

class PentagiViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val health: StateFlow<PentagiHealth> = g.pentagi.health
    val busy: StateFlow<Boolean> = g.pentagi.busy
    val lastError: StateFlow<String?> = g.pentagi.lastError
    val remoteLogs: StateFlow<List<String>> = g.pentagi.remoteLogs
    val configKeysSet: StateFlow<List<String>> = g.pentagi.configKeysSet

    private val _config = MutableStateFlow(PentagiConfigUi())
    val config: StateFlow<PentagiConfigUi> = _config

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        refreshConfig()
    }

    fun onScreenOpen() {
        g.pentagi.ensureProjectEntry()
        refreshConfig()
        if (g.pentagi.isManagerConfigured()) g.pentagi.refresh()
    }

    fun refreshConfig() {
        val s = g.settings
        _config.value = PentagiConfigUi(
            enabled = s.pentagiEnabled,
            managerUrl = s.pentagiManagerUrl,
            remoteUrl = s.pentagiRemoteUrl,
            publicUrl = s.pentagiPublicUrl,
            provider = s.pentagiLlmProvider,
            hasManagerToken = g.secrets.has(SecretStore.KEY_PENTAGI_MANAGER_TOKEN),
            hasLlmKey = g.secrets.has(SecretStore.KEY_PENTAGI_LLM_KEY),
            hasApiToken = g.secrets.has(SecretStore.KEY_PENTAGI_API_TOKEN),
            projectEntryExists = g.projectStore.get("pentagi") != null
        )
    }

    fun refresh() = g.pentagi.refresh()
    fun start() = g.pentagi.start()
    fun stop() = g.pentagi.stop()
    fun restart() = g.pentagi.restart()
    fun fetchLogs(tail: Int = 100) = g.pentagi.fetchLogs(tail)

    fun setEnabled(v: Boolean) {
        g.settings.pentagiEnabled = v
        refreshConfig()
    }

    /**
     * Validates everything first; on any error nothing is saved and the
     * error is surfaced. Secret fields are write-only: a blank field means
     * "keep the saved value", a non-blank field replaces it. Secret values
     * are never copied into UI state or messages.
     */
    fun saveConfig(
        managerUrl: String,
        remoteUrl: String,
        publicUrl: String,
        provider: String,
        llmKey: String,
        managerToken: String,
        apiToken: String
    ) {
        val mu = managerUrl.trim()
        val ru = remoteUrl.trim()
        val pu = publicUrl.trim()
        if (mu.isNotEmpty()) PentagiValidator.validateManagerUrl(mu)?.let {
            _messages.tryEmit(it); return
        }
        if (ru.isNotEmpty()) PentagiValidator.validateRemoteUrl(ru)?.let {
            _messages.tryEmit(it); return
        }
        if (pu.isNotEmpty()) PentagiValidator.validateUrl(pu, "Public URL")?.let {
            _messages.tryEmit(it); return
        }
        if (llmKey.isNotBlank() && !PentagiValidator.isValidProviderKey(llmKey)) {
            _messages.tryEmit(PentagiValidator.validateProviderKey(llmKey) ?: "Invalid API key"); return
        }
        if (managerToken.isNotBlank() && !PentagiValidator.isValidManagerToken(managerToken)) {
            _messages.tryEmit("Manager token must be at least 24 characters with no spaces"); return
        }
        val prov = PentagiLlmProvider.fromId(provider)

        g.settings.pentagiManagerUrl = mu
        g.settings.pentagiRemoteUrl = ru
        g.settings.pentagiPublicUrl = pu
        g.settings.pentagiLlmProvider = prov.id
        if (llmKey.isNotBlank()) g.secrets.put(SecretStore.KEY_PENTAGI_LLM_KEY, llmKey.trim())
        if (managerToken.isNotBlank()) g.secrets.put(SecretStore.KEY_PENTAGI_MANAGER_TOKEN, managerToken.trim())
        if (apiToken.isNotBlank()) g.secrets.put(SecretStore.KEY_PENTAGI_API_TOKEN, apiToken.trim())
        refreshConfig()
        _messages.tryEmit("PentAGI configuration saved")
        viewModelScope.launch { g.pentagi.ensureProjectEntry() }
    }

    fun providerLabel(id: String): String = PentagiLlmProvider.fromId(id).label
    fun providerEnvKey(id: String): String = PentagiLlmProvider.fromId(id).envKey
}
