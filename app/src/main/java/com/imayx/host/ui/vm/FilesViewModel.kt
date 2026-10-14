package com.imayx.host.ui.vm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.files.FileItem
import com.imayx.host.files.FileOps
import com.imayx.host.files.SortKey
import com.imayx.host.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesUi(
    val loading: Boolean = true,
    val project: String? = null,
    val projects: List<String> = emptyList(),
    val path: String = "",
    val items: List<FileItem> = emptyList(),
    val error: String? = null,
    val searching: Boolean = false,
    val query: String = "",
    val results: List<FileItem> = emptyList(),
    val sort: SortKey = SortKey.NAME,
    val ascending: Boolean = true
)

data class Clip(val project: String, val rel: String, val name: String, val cut: Boolean)

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    private val _ui = MutableStateFlow(FilesUi())
    val ui: StateFlow<FilesUi> = _ui

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var searchJob: Job? = null

    private fun ops(project: String) = FileOps(g.projectStore.projectDir(project))

    /** (Re)load the project list and the current folder. */
    fun load() {
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) { g.projects.list().map { it.name } }
            var project = _ui.value.project
            if (project == null || project !in names) {
                project = g.settings.activeProject.takeIf { it in names } ?: names.firstOrNull()
                _ui.value = _ui.value.copy(project = project, path = "")
            }
            _ui.value = _ui.value.copy(projects = names)
            refreshFolder()
        }
    }

    fun selectProject(name: String) {
        _ui.value = _ui.value.copy(project = name, path = "", searching = false, query = "", results = emptyList())
        refreshFolder()
    }

    fun refreshFolder() {
        val st = _ui.value
        val project = st.project
        if (project == null) {
            _ui.value = st.copy(loading = false, items = emptyList(), error = null)
            return
        }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { ops(project).list(st.path, st.sort, st.ascending) }
                _ui.value = _ui.value.copy(loading = false, items = items, error = null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Could not read folder")
            }
        }
    }

    fun openFolder(rel: String) {
        _ui.value = _ui.value.copy(path = rel, searching = false, query = "", results = emptyList())
        refreshFolder()
    }

    fun up() {
        val p = _ui.value.path
        if (p.isEmpty()) return
        openFolder(p.substringBeforeLast('/', ""))
    }

    fun setSort(key: SortKey) {
        val st = _ui.value
        _ui.value = if (st.sort == key) st.copy(ascending = !st.ascending) else st.copy(sort = key, ascending = true)
        refreshFolder()
    }

    fun toggleSearch() {
        val s = _ui.value
        _ui.value = s.copy(searching = !s.searching, query = "", results = emptyList())
    }

    fun setQuery(q: String) {
        _ui.value = _ui.value.copy(query = q)
        searchJob?.cancel()
        val project = _ui.value.project ?: return
        if (q.isBlank()) {
            _ui.value = _ui.value.copy(results = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            val res = withContext(Dispatchers.IO) {
                try {
                    ops(project).search(_ui.value.path, q)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            _ui.value = _ui.value.copy(results = res)
        }
    }

    private fun mutate(success: String?, block: (FileOps, String) -> Unit) {
        val project = _ui.value.project ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block(ops(project), project) }
                if (success != null) _messages.emit(success)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Operation failed")
            }
            refreshFolder()
            if (_ui.value.searching) setQuery(_ui.value.query)
        }
    }

    fun createFolder(name: String) = mutate("Folder created") { o, _ -> o.createFolder(_ui.value.path, name) }
    fun createFile(name: String) = mutate("File created") { o, _ -> o.createFile(_ui.value.path, name) }
    fun rename(item: FileItem, newName: String) = mutate("Renamed") { o, _ -> o.rename(item.relPath, newName) }
    fun delete(item: FileItem) = mutate("Deleted") { o, _ -> o.delete(item.relPath) }
    fun duplicate(item: FileItem) = mutate("Duplicated") { o, _ -> o.duplicate(item.relPath) }

    fun copy(item: FileItem) {
        val p = _ui.value.project ?: return
        _clip.value = Clip(p, item.relPath, item.name, cut = false)
        _messages.tryEmit("Copied \"${item.name}\" — open the destination folder and tap Paste")
    }

    fun cut(item: FileItem) {
        val p = _ui.value.project ?: return
        _clip.value = Clip(p, item.relPath, item.name, cut = true)
        _messages.tryEmit("Ready to move \"${item.name}\" — open the destination folder and tap Paste")
    }

    fun clearClip() {
        _clip.value = null
    }

    fun paste() {
        val clip = _clip.value ?: return
        val dest = _ui.value.project ?: return
        if (clip.project != dest) {
            _messages.tryEmit("Copy/move works inside one project at a time")
            return
        }
        val destPath = _ui.value.path
        mutate(if (clip.cut) "Moved" else "Pasted") { o, _ ->
            if (clip.cut) o.move(clip.rel, destPath) else o.copy(clip.rel, destPath)
        }
        _clip.value = null
    }

    fun exportTo(item: FileItem, uri: Uri) {
        val project = _ui.value.project ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Cannot write to the selected location")
                    out.use { ops(project).exportTo(item.relPath, it) }
                }
                _messages.emit("Saved")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Download failed")
            }
        }
    }

    fun shareIntent(item: FileItem): Intent? {
        val project = _ui.value.project ?: return null
        return try {
            ops(project).shareIntent(getApplication<Application>(), item.relPath)
        } catch (e: Exception) {
            _messages.tryEmit(e.message ?: "Cannot share this item")
            null
        }
    }

    fun projectDirName(): String? = _ui.value.project
}
