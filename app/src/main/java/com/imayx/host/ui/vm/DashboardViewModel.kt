package com.imayx.host.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.data.HostState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.TunnelState
import com.imayx.host.graph
import com.imayx.host.runtime.ResourceSnapshot
import com.imayx.host.service.HostService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val host: StateFlow<HostState> = g.host.state
    val tunnel: StateFlow<TunnelState> = g.tunnel.state

    private val _projects = MutableStateFlow<List<ProjectConfig>>(emptyList())
    val projects: StateFlow<List<ProjectConfig>> = _projects

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    val activeProject: StateFlow<String> get() = _active
    private val _active = MutableStateFlow(g.settings.activeProject)

    val resources: StateFlow<ResourceSnapshot?> = flow {
        while (true) {
            emit(sampleNow())
            delay(5000)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val quickTunnel: Boolean get() = g.settings.quickTunnel
    val defaultDomain: String get() = g.settings.defaultDomain

    private fun sampleNow(): ResourceSnapshot {
        val name = g.host.currentProject() ?: g.settings.activeProject.ifBlank { null }
        val dir = name?.let { g.projectStore.projectDir(it) }
        return g.monitor.sample(g.host.pids(), dir)
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { g.projects.list() }
            _projects.value = list
            var active = g.settings.activeProject
            if (list.none { it.name == active }) {
                active = list.firstOrNull()?.name ?: ""
                g.settings.activeProject = active
            }
            _active.value = active
            _loaded.value = true
        }
    }

    fun setActive(name: String) {
        g.projects.setActive(name)
        _active.value = name
    }

    fun config(name: String): ProjectConfig? = _projects.value.firstOrNull { it.name == name }

    fun startHost() {
        val name = _active.value.ifBlank { null } ?: return
        HostService.start(getApplication<Application>(), name)
    }

    fun stopHost() {
        HostService.stop(getApplication<Application>())
    }

    fun restartHost() {
        val name = g.host.currentProject() ?: _active.value.ifBlank { null } ?: return
        HostService.start(getApplication<Application>(), name)
    }
}
