package com.imayx.host.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.backup.BackupInfo
import com.imayx.host.graph
import com.imayx.host.projects.ProjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupsUi(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val projects: List<String> = emptyList(),
    val selected: String = "",
    val backups: List<BackupInfo> = emptyList()
)

class BackupsViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val _ui = MutableStateFlow(BackupsUi())
    val ui: StateFlow<BackupsUi> = _ui
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun refresh() {
        viewModelScope.launch {
            val (names, list) = withContext(Dispatchers.IO) { g.projects.list().map { it.name } to g.backups.list() }
            val cur = _ui.value
            val sel = when {
                cur.selected in names -> cur.selected
                g.settings.activeProject in names -> g.settings.activeProject
                else -> names.firstOrNull() ?: ""
            }
            _ui.value = cur.copy(loading = false, projects = names, selected = sel, backups = list)
        }
    }

    fun select(name: String) {
        _ui.value = _ui.value.copy(selected = name)
    }

    private fun work(label: String, block: () -> String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: ProjectException) {
                    e.message ?: "$label failed"
                } catch (e: Exception) {
                    e.message ?: "$label failed"
                }
            }
            _messages.emit(msg)
            _ui.value = _ui.value.copy(busy = false)
            refresh()
        }
    }

    fun create() {
        val p = _ui.value.selected
        if (p.isBlank()) return
        work("Backup") { "Backup created (${g.backups.create(p).file.name})" }
    }

    fun restore(b: BackupInfo, target: String, replace: Boolean) {
        if (g.host.currentProject() == target && g.host.state.value.state != com.imayx.host.data.ProcessState.STOPPED) {
            _messages.tryEmit("Stop the host before restoring over the running project")
            return
        }
        work("Restore") { "Restored into \"${g.backups.restore(b.file, target, replace)}\"" }
    }

    fun export(b: BackupInfo, uri: Uri) {
        work("Export") {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: throw ProjectException("Cannot write to the selected location")
            out.use { g.backups.export(b.file, it) }
            "Backup exported"
        }
    }

    fun delete(b: BackupInfo) = work("Delete") {
        g.backups.delete(b.file)
        "Backup deleted"
    }
}
