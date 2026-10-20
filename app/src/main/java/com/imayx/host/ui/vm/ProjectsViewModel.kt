package com.imayx.host.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectInfo
import com.imayx.host.data.RuntimeType
import com.imayx.host.graph
import com.imayx.host.node.AyxBackendRuntime
import com.imayx.host.projects.ProjectManager
import com.imayx.host.projects.ZipInfo
import com.imayx.host.service.HostService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectsUi(
    val loading: Boolean = true,
    val items: List<ProjectInfo> = emptyList(),
    val error: String? = null,
    /** Non-null while a long operation (import/export/duplicate) runs. */
    val busy: String? = null,
    val progress: Float? = null
)

data class ImportStage(
    val file: File,
    val info: ZipInfo,
    val sourceName: String,
    val suggestedName: String,
    /** Set when the ZIP looks like a Node.js server (pre-extraction guess). */
    val detection: ProjectManager.ServerDetection?
)

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    private val _ui = MutableStateFlow(ProjectsUi())
    val ui: StateFlow<ProjectsUi> = _ui

    private val _stage = MutableStateFlow<ImportStage?>(null)
    val stage: StateFlow<ImportStage?> = _stage

    /** Name of a project that was just imported, so the UI can offer "Start Hosting". */
    private val _imported = MutableStateFlow<String?>(null)
    val imported: StateFlow<String?> = _imported

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Result of the last one-off node script run; shown in a dialog, null when dismissed. */
    private val _scriptResult = MutableStateFlow<String?>(null)
    val scriptResult: StateFlow<String?> = _scriptResult

    fun clearScriptResult() {
        _scriptResult.value = null
    }

    init {
        viewModelScope.launch { g.host.notices.collect { _messages.emit(it) } }
    }

    val activeProject: String get() = g.settings.activeProject
    val hostState get() = g.host.state

    fun nodeEnv(project: String): Map<String, String> = g.secrets.getEnv(project)

    fun saveNodeEnv(project: String, env: Map<String, String>) {
        g.secrets.setEnv(project, env)
        _messages.tryEmit("Environment saved securely")
    }

    fun nodeInstalled(): Boolean = g.nodeInstaller.isInstalled()

    /** The port the primary (active) project always binds to — the tunnel target. */
    fun primaryPort(): Int = g.settings.primaryPort

    /** True when the built-in Node.js (libnode.so) is bundled and SERVER projects can run. */
    fun serverAvailable(): Boolean = com.imayx.host.node.ServerRuntime.isAvailable()

    // ---- one-off node scripts (backend setup) ----------------------------------------------------

    private suspend fun runNodeScriptSuspend(name: String, script: String, args: List<String>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val cfg = g.projects.get(name) ?: return@withContext false to "Project not found"
            if (cfg.runtime != RuntimeType.NODE) return@withContext false to "Not a Node project"
            try {
                val done = CompletableDeferred<Pair<Boolean, String>>()
                g.runtimes.scriptRunner.run(cfg, script, args) { code, tail ->
                    done.complete((code == 0) to tail.ifBlank { "Process exited with code $code" })
                }
                done.await()
            } catch (e: CancellationException) {
                g.runtimes.scriptRunner.stop()
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "Could not run script")
            }
        }

    /** One-shot `node <script> [args…]` for NODE projects. The result appears in [scriptResult]. */
    fun runNodeScript(name: String, script: String, args: List<String>) {
        if (g.runtimes.scriptRunner.isRunning()) {
            _messages.tryEmit("A script is already running — wait for it to finish")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "Running node $script…")
            val (ok, tail) = runNodeScriptSuspend(name, script, args)
            _ui.value = _ui.value.copy(busy = null)
            _scriptResult.value = (if (ok) "Done — " else "Failed — ") + "node $script\n\n$tail"
        }
    }

    /** Parses a free-form command line like `scripts/migrate.js --flag` and runs it one-shot. */
    fun runNodeCommandLine(name: String, commandLine: String) {
        val parts = commandLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val script = parts[0]
        if (script.startsWith("/") || script.contains("..")) {
            _messages.tryEmit("Refusing unsafe script path")
            return
        }
        runNodeScript(name, script, parts.drop(1))
    }

    /**
     * Creates an AyX Group backend admin account via the token-guarded
     * `POST /v1/local/admin/bootstrap` endpoint on the embedded backend.
     * This goes over loopback HTTP — never a second node process (node::Start
     * can only run once per OS process; a second call SIGABRTs the app).
     * The password is sent only in the request body and is never stored.
     * [name] is the project context the dialog was opened from (unused — the
     * backend is no longer a hosted project).
     */
    fun createBackendAdmin(name: String, username: String, password: String) {
        if (username.length !in 3..64 || password.length < 12) {
            _messages.tryEmit("Username needs 3–64 characters and the password at least 12")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "Creating admin account…")
            val (ok, msg) = withContext(Dispatchers.IO) {
                val done = CompletableDeferred<Pair<Boolean, String>>()
                try {
                    AyxBackendRuntime.bootstrapAdmin(
                        getApplication(), username, password
                    ) { ok, message -> done.complete(ok to message) }
                } catch (e: Exception) {
                    done.complete(false to (e.message ?: "Could not create admin"))
                }
                done.await()
            }
            _ui.value = _ui.value.copy(busy = null)
            _scriptResult.value = if (ok) "Admin \"$username\" created.\n\n$msg"
            else "Could not create the admin account.\n\n$msg"
        }
    }

    private fun isHosted(name: String): Boolean {
        val h = g.host.state.value
        return (h.project == name || name in h.extras) && h.state != ProcessState.STOPPED
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { g.projects.listInfo() }
                _ui.value = _ui.value.copy(loading = false, items = items, error = null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Could not load projects")
            }
        }
    }

    private fun runOp(label: String, successMessage: String?, block: () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = label, progress = null)
            try {
                withContext(Dispatchers.IO) { block() }
                if (successMessage != null) _messages.emit(successMessage)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Operation failed")
            } finally {
                _ui.value = _ui.value.copy(busy = null, progress = null)
                refresh()
            }
        }
    }

    fun create(name: String, runtime: RuntimeType) =
        runOp("Creating project…", "Project \"$name\" created") { g.projects.create(name, runtime) }

    fun rename(old: String, new: String) {
        if (isHosted(old)) {
            _messages.tryEmit("Stop hosting before renaming this project")
            return
        }
        runOp("Renaming…", "Renamed to \"$new\"") { g.projects.rename(old, new) }
    }

    fun delete(name: String) {
        if (isHosted(name)) {
            _messages.tryEmit("Stop hosting before deleting this project")
            return
        }
        runOp("Deleting…", "Project \"$name\" deleted") { g.projects.delete(name) }
    }

    fun duplicate(name: String, newName: String) =
        runOp("Duplicating…", "Duplicated as \"$newName\"") { g.projects.duplicate(name, newName) }

    fun setActive(name: String) {
        g.projects.setActive(name)
        refresh()
        _messages.tryEmit("\"$name\" is now the active project")
    }

    fun saveConfig(cfg: ProjectConfig) {
        if (isHosted(cfg.name)) {
            _messages.tryEmit("Saved. Restart the host to apply changes")
        } else {
            _messages.tryEmit("Project settings saved")
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { g.projects.save(cfg) }
            refresh()
        }
    }

    fun suggestedPort(): Int = g.settings.defaultPort

    // ---- Import ---------------------------------------------------------------------------------

    fun stageImport(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "Reading ZIP…", progress = null)
            try {
                val stage = withContext(Dispatchers.IO) {
                    val (file, info) = g.projects.stageZip(uri)
                    ImportStage(
                        file, info, g.projects.displayName(uri), g.projects.suggestName(uri),
                        g.projects.analyzeZipForServer(info)
                    )
                }
                _stage.value = stage
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Could not read the selected file")
            } finally {
                _ui.value = _ui.value.copy(busy = null)
            }
        }
    }

    fun cancelImport() {
        _stage.value?.file?.delete()
        _stage.value = null
    }

    fun confirmImport(name: String) {
        val st = _stage.value ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "Importing…", progress = 0f)
            try {
                val result = withContext(Dispatchers.IO) {
                    g.projects.importStaged(st.file, st.info, name) { done, total ->
                        if (total > 0) _ui.value = _ui.value.copy(progress = done.toFloat() / total)
                    }
                }
                _stage.value = null
                _imported.value = result.config.name
                for (w in result.warnings) _messages.emit(w)
                if (result.config.runtime == RuntimeType.SERVER && result.config.servers.size > 1) {
                    _messages.emit(
                        "\"${result.config.name}\": ${result.config.servers.size} servers detected — " +
                            result.config.servers.joinToString { "${it.name} :${it.port}" } +
                            ". Ports and subdomains were assigned automatically."
                    )
                } else if (result.config.runtime == RuntimeType.SERVER) {
                    _messages.emit(
                        "Server \"${result.config.name}\" ready (entry: ${result.config.entryFile}, " +
                            "port ${result.config.port}). " +
                            "Add API keys in Settings → Environment secrets, then tap Start Hosting."
                    )
                } else {
                    _messages.emit("Project ready")
                    val hasPkg = withContext(Dispatchers.IO) {
                        g.projectStore.projectDir(result.config.name).walkTopDown().maxDepth(4)
                            .onEnter { it.name != "node_modules" }
                            .any { it.isFile && it.name == "package.json" }
                    }
                    if (hasPkg && com.imayx.host.ai.AiFixer.isConfigured(g.secrets)) {
                        _messages.emit("package.json found but no server detected — asking AI to read the project…")
                        rescanServers(result.config.name, useAi = true)
                    }
                }
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Import failed")
            } finally {
                _ui.value = _ui.value.copy(busy = null, progress = null)
                refresh()
            }
        }
    }

    /**
     * Re-detect the servers (and ports) of a project. The normal scanner runs first; when it finds nothing
     * (or [useAi] is set) Groq reads the file tree and package.json files and names the servers.
     */
    fun rescanServers(name: String, useAi: Boolean = false) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "Scanning $name…")
            try {
                var cfg: ProjectConfig? = null
                if (!useAi) {
                    cfg = try {
                        withContext(Dispatchers.IO) { g.projects.rescanServers(name) }
                    } catch (_: com.imayx.host.projects.ProjectException) {
                        null
                    }
                }
                if (cfg == null) {
                    if (!com.imayx.host.ai.AiFixer.isConfigured(g.secrets)) {
                        _messages.emit("No server found. Add a Groq API key (Settings → AI auto-fix), then use Detect servers (AI).")
                        return@launch
                    }
                    _ui.value = _ui.value.copy(busy = "AI reading $name…")
                    val found = com.imayx.host.ai.AiFixer.detectServers(
                        getApplication<Application>(), g.settings, g.secrets, g.projectStore.projectDir(name)
                    )
                    if (found.isEmpty()) {
                        _messages.emit("AI also found no Node.js server in $name. Check the folder in Files.")
                        return@launch
                    }
                    cfg = withContext(Dispatchers.IO) { g.projects.rescanServers(name, found) }
                }
                _messages.emit("$name: ${cfg.servers.size} server(s) — " + cfg.servers.joinToString { "${it.name} :${it.port}" })
            } catch (e: Exception) {
                if (e !is CancellationException) _messages.emit(e.message ?: "Scan failed")
            } finally {
                _ui.value = _ui.value.copy(busy = null)
                refresh()
            }
        }
    }

    fun clearImported() {
        _imported.value = null
    }

    fun startHosting(name: String) {
        g.projects.setActive(name)
        HostService.start(getApplication<Application>(), name)
        _imported.value = null
    }

    /**
     * Manual AI fix: Groq reads the project's recent errors and patches its files.
     * Runs in the background; progress and the result arrive as messages.
     */
    fun aiFix(name: String) {
        val app = getApplication<Application>()
        if (!com.imayx.host.ai.AiFixer.isConfigured(g.secrets)) {
            viewModelScope.launch { _messages.emit("Add a Groq API key first: Settings → AI auto-fix") }
            return
        }
        _ui.value = _ui.value.copy(busy = "AI fixing $name…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = com.imayx.host.ai.AiFixer.fixProject(
                    app, g.settings, g.secrets, g.backups, g.projectStore, name,
                    "manual AI Fix requested by user",
                    onProgress = { step -> _ui.value = _ui.value.copy(busy = "AI fixing $name… $step") }
                )
                _messages.emit(
                    if (res.ok && res.editsApplied > 0)
                        "AI fix: ${res.editsApplied} edit(s) applied to $name (backup ${res.backupName}). Restart hosting to apply."
                    else if (res.ok)
                        "AI fix: ${res.summary.take(200)}"
                    else
                        "AI fix failed: ${res.summary.take(200)}"
                )
            } catch (e: Exception) {
                if (e !is CancellationException) _messages.emit("AI fix error: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = null)
            }
        }
    }

    /** Multi-start: hosts every selected project at the same time (replaces what was running). */
    fun startSelected(names: List<String>) {
        if (names.isEmpty()) return
        val picked = _ui.value.items.map { it.config }.filter { it.name in names }
        // Mirror HostController.doStartMany: the first Cloudflare-enabled project becomes the
        // primary and always binds the primary (tunnel) port; the rest keep their own ports.
        // Two projects on the same EFFECTIVE port can never run together — name them.
        val primary = picked.sortedByDescending { it.cloudflareEnabled }.firstOrNull()
        val effPorts = picked.associate { it.name to if (it.name == primary?.name) g.settings.primaryPort else it.port }
        val clash = effPorts.entries.groupBy({ it.value }, { it.key }).filter { it.value.size > 1 }
        if (clash.isNotEmpty()) {
            val msg = clash.entries.joinToString("; ") { (port, list) ->
                list.joinToString(" + ") + " would share port $port"
            }
            _messages.tryEmit(
                "$msg. Change a port in Project Settings first " +
                    "(the primary project always uses port ${g.settings.primaryPort})."
            )
            return
        }
        g.projects.setActive(names.first())
        HostService.startMany(getApplication<Application>(), names)
        _imported.value = null
    }

    fun stopHosting(name: String) {
        g.host.stopOne(name)
    }

    // ---- Export ---------------------------------------------------------------------------------

    fun exportTo(project: String, uri: Uri) {
        runOp("Exporting ZIP…", "Project exported") {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot write to the selected location")
            out.use { g.projects.exportZip(project, it) }
        }
    }
}
