package com.imayx.host.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.data.ThemeMode
import com.imayx.host.graph
import com.imayx.host.utils.PathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUi(
    val theme: ThemeMode = ThemeMode.AMOLED,
    val port: Int = 3000,
    val defaultProject: String = "",
    val defaultDomain: String = "",
    val projects: List<String> = emptyList(),
    val startOnBoot: Boolean = false,
    val ayxBackend: Boolean = true,
    val notifications: Boolean = true,
    val autosave: Boolean = false,
    val cloudflaredUrl: String = "",
    val nodeUrl: String = "",
    val nodeInstalled: Boolean = false,
    val nodeVersion: String? = null,
    val nodeSource: String? = null,
    val nodeBusy: Boolean = false,
    val nodePercent: Int? = null,
    val lockEnabled: Boolean = false,
    val biometric: Boolean = false,
    val projectsBytes: Long = 0,
    val backupsBytes: Long = 0,
    val binariesBytes: Long = 0,
    val cacheBytes: Long = 0
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun refresh() {
        val s = g.settings
        _ui.value = _ui.value.copy(
            theme = s.themeMode, port = s.defaultPort, defaultProject = s.activeProject, defaultDomain = s.defaultDomain,
            startOnBoot = s.startOnBoot, ayxBackend = s.ayxBackendEnabled, notifications = s.notificationsEnabled, autosave = s.autosave,
            cloudflaredUrl = s.cloudflaredUrl, nodeUrl = s.nodeRuntimeUrl,
            lockEnabled = g.appLock.isEnabled(), biometric = g.appLock.isBiometricEnabled()
        )
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val r = withContext(Dispatchers.IO) {
                val names = g.projects.list().map { it.name }
                val inst = g.nodeInstaller
                val ver = if (inst.isInstalled()) inst.version() else null
                fun size(f: File) = if (f.exists()) PathUtil.folderSize(f) else 0L
                SettingsUi(
                    projects = names,
                    nodeInstalled = inst.isInstalled(), nodeVersion = ver, nodeSource = inst.source(),
                    projectsBytes = size(File(ctx.filesDir, "projects")),
                    backupsBytes = size(File(ctx.filesDir, "backups")),
                    binariesBytes = size(File(ctx.filesDir, "bin")) + size(File(ctx.filesDir, "runtime")),
                    cacheBytes = size(ctx.cacheDir)
                )
            }
            _ui.value = _ui.value.copy(
                projects = r.projects, nodeInstalled = r.nodeInstalled, nodeVersion = r.nodeVersion, nodeSource = r.nodeSource,
                projectsBytes = r.projectsBytes, backupsBytes = r.backupsBytes, binariesBytes = r.binariesBytes, cacheBytes = r.cacheBytes
            )
        }
    }

    fun setTheme(t: ThemeMode) { g.settings.themeMode = t; refresh() }
    fun setPort(p: Int) { g.settings.defaultPort = p; refresh() }
    fun setDefaultProject(n: String) { g.settings.activeProject = n; refresh() }
    fun setDefaultDomain(d: String) { g.settings.defaultDomain = d; refresh() }
    fun setStartOnBoot(v: Boolean) { g.settings.startOnBoot = v; refresh() }
    fun setAyxBackend(v: Boolean) { g.settings.ayxBackendEnabled = v; refresh() }
    fun setNotifications(v: Boolean) { g.settings.notificationsEnabled = v; refresh() }
    fun setAutosave(v: Boolean) { g.settings.autosave = v; refresh() }
    fun setCloudflaredUrl(v: String) { g.settings.cloudflaredUrl = v; refresh() }
    fun setNodeUrl(v: String) { g.settings.nodeRuntimeUrl = v; refresh() }

    fun setPin(pin: String) {
        g.appLock.setPin(pin)
        g.appLock.markUnlocked()
        g.appLock.markSensitiveUnlocked()
        _messages.tryEmit("App lock enabled")
        refresh()
    }

    fun disableLock() {
        g.appLock.disable()
        _messages.tryEmit("App lock disabled")
        refresh()
    }

    fun setBiometric(v: Boolean) { g.appLock.setBiometricEnabled(v); refresh() }

    fun installNode() {
        if (_ui.value.nodeBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(nodeBusy = true, nodePercent = 0)
            val url = g.settings.nodeRuntimeUrl
            val r = withContext(Dispatchers.IO) {
                g.nodeInstaller.installFromUrl(url) { p -> _ui.value = _ui.value.copy(nodePercent = p) }
            }
            _ui.value = _ui.value.copy(nodeBusy = false, nodePercent = null)
            _messages.emit(r.message)
            refresh()
        }
    }

    fun importNode(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(nodeBusy = true, nodePercent = null)
            val r = withContext(Dispatchers.IO) {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                if (input == null) com.imayx.host.runtime.InstallResult(false, "Cannot open the selected file")
                else input.use { g.nodeInstaller.installFromStream(it) }
            }
            _ui.value = _ui.value.copy(nodeBusy = false)
            _messages.emit(r.message)
            refresh()
        }
    }

    fun removeNode() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { g.nodeInstaller.uninstall() }
            _messages.emit("Node runtime removed")
            refresh()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            _messages.emit("Cache cleared")
            refresh()
        }
    }
}
