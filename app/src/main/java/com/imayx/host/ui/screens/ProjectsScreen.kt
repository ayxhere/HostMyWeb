package com.imayx.host.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectInfo
import com.imayx.host.data.RuntimeType
import com.imayx.host.graph
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.ConfirmDialog
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.ErrorState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxChip
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.KeyValueRow
import com.imayx.host.ui.components.LoadingState
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.Meter
import com.imayx.host.ui.components.PromptDialog
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.components.SecretField
import com.imayx.host.ui.components.StatusDot
import com.imayx.host.ui.components.StatusPill
import com.imayx.host.ui.components.SwitchRow
import com.imayx.host.ui.hostStatusColor
import com.imayx.host.ui.hostStatusText
import com.imayx.host.ui.isValidHostname
import com.imayx.host.ui.cleanDomain
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.ImportStage
import com.imayx.host.ui.vm.ProjectsViewModel
import com.imayx.host.utils.Fmt
import com.imayx.host.utils.PathUtil

private fun nameError(n: String): String? =
    if (PathUtil.isValidProjectName(n)) null else "Letters, digits, '.', '_' or '-' only (max 48, start with a letter or digit)"

@Composable
fun ProjectsScreen(
    onPreview: (String) -> Unit,
    vm: ProjectsViewModel = viewModel()
) {
    val c = Ix.c
    val ui by vm.ui.collectAsStateWithLifecycle()
    val host by vm.hostState.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val imported by vm.imported.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val gate = LocalGate.current
    val lock = LocalContext.current.graph.appLock

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var showAdd by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var configFor by remember { mutableStateOf<ProjectConfig?>(null) }
    var envFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var duplicateFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var exportFor by remember { mutableStateOf<String?>(null) }
    val scriptResult by vm.scriptResult.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.stageImport(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val p = exportFor
        if (uri != null && p != null) vm.exportTo(p, uri)
        exportFor = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            "Projects",
            subtitle = if (selecting) "${selected.size} selected" else "${ui.items.size} website${if (ui.items.size == 1) "" else "s"}",
            actions = {
                if (selecting) {
                    IxButton("Cancel", onClick = { selecting = false; selected.clear() }, kind = BtnKind.Ghost)
                    Spacer(Modifier.width(8.dp))
                    IxButton(
                        "Start ${selected.size}",
                        onClick = {
                            vm.startSelected(selected.toList())
                            selecting = false
                            selected.clear()
                        },
                        icon = Icons.Rounded.PlayArrow,
                        enabled = selected.isNotEmpty()
                    )
                } else {
                    if (ui.items.size > 1) {
                        IxButton("Select", onClick = { selecting = true }, kind = BtnKind.Secondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    IxButton("Add", onClick = { showAdd = true }, icon = Icons.Rounded.Add)
                }
            }
        )

        if (ui.busy != null) {
            IxCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(ui.busy ?: "", color = c.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Meter("Working", ui.progress, if (ui.progress != null) "${((ui.progress ?: 0f) * 100).toInt()}%" else "…")
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            ui.loading -> LoadingState("Loading projects…")
            ui.error != null -> ErrorState("Could not load projects", ui.error ?: "", onRetry = { vm.refresh() })
            ui.items.isEmpty() && imported == null -> EmptyState(
                icon = Icons.Rounded.Language,
                title = "No websites hosted yet",
                subtitle = "Import your first ZIP",
                actionLabel = "Add project",
                onAction = { showAdd = true }
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (imported != null) {
                    item {
                        IxCard {
                            Text("Project ready", color = c.ok, style = MaterialTheme.typography.titleMedium)
                            Text("\"$imported\" was imported.", color = c.soft, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IxButton("Start Hosting", onClick = { vm.startHosting(imported ?: "") }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                                IxButton("Dismiss", onClick = { vm.clearImported() }, kind = BtnKind.Secondary, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                items(ui.items, key = { it.config.name }) { info ->
                    val isPrimary = host.project == info.config.name
                    val hostedState = when {
                        isPrimary -> host.state
                        info.config.name in host.extras && host.state == ProcessState.RUNNING -> ProcessState.RUNNING
                        else -> ProcessState.STOPPED
                    }
                    // The primary project always serves on the primary (tunnel) port while running.
                    val effPort = if (isPrimary && hostedState != ProcessState.STOPPED) vm.primaryPort() else info.config.port
                    ProjectCard(
                        info = info,
                        isActive = vm.activeProject == info.config.name,
                        isPrimary = isPrimary,
                        effPort = effPort,
                        hostedState = hostedState,
                        selecting = selecting,
                        selected = info.config.name in selected,
                        onToggle = {
                            val n = info.config.name
                            if (n in selected) selected.remove(n) else selected.add(n)
                        },
                        onStart = { vm.startHosting(info.config.name) },
                        onStop = { vm.stopHosting(info.config.name) },
                        onSettings = { configFor = info.config },
                        onPreview = { onPreview("http://127.0.0.1:$effPort/") },
                        onSetActive = { vm.setActive(info.config.name) },
                        onRename = { renameFor = info.config.name },
                        onDuplicate = { duplicateFor = info.config.name },
                        onExport = {
                            exportFor = info.config.name
                            exportLauncher.launch(info.config.name + ".zip")
                        },
                        onDelete = { deleteFor = info.config.name },
                        onAiFix = { vm.aiFix(info.config.name) }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = c.card,
            titleContentColor = c.text,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            title = { Text("Add project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IxButton(
                        "Import ZIP",
                        onClick = {
                            showAdd = false
                            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        },
                        icon = Icons.Rounded.Upload,
                        modifier = Modifier.fillMaxWidth()
                    )
                    IxButton(
                        "Create empty project",
                        onClick = {
                            showAdd = false
                            showCreate = true
                        },
                        icon = Icons.Rounded.Add,
                        kind = BtnKind.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {},
            dismissButton = { IxButton("Close", { showAdd = false }, kind = BtnKind.Ghost) }
        )
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, runtime ->
                showCreate = false
                vm.create(name, runtime)
            }
        )
    }

    stage?.let { st ->
        ImportDialog(
            stage = st,
            existing = ui.items.map { it.config.name },
            onCancel = { vm.cancelImport() },
            onImport = { name -> vm.confirmImport(name) },
            busy = ui.busy != null
        )
    }

    configFor?.let { cfg ->
        ConfigDialog(
            cfg = cfg,
            nodeInstalled = vm.nodeInstalled(),
            serverAvailable = vm.serverAvailable(),
            onDismiss = { configFor = null },
            onSave = {
                vm.saveConfig(it)
                configFor = null
            },
            onEnv = {
                configFor = null
                gate.request(lock) { envFor = cfg.name }
            },
            onCreateAdmin = { u, p -> vm.createBackendAdmin(cfg.name, u, p) },
            onRunScript = { cmd -> vm.runNodeCommandLine(cfg.name, cmd) }
        )
    }

    scriptResult?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.clearScriptResult() },
            containerColor = c.card,
            titleContentColor = c.text,
            textContentColor = c.soft,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            title = { Text("Script result") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(result, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { IxButton("Close", { vm.clearScriptResult() }) },
            dismissButton = {}
        )
    }

    envFor?.let { project ->
        EnvDialog(
            initial = vm.nodeEnv(project),
            onDismiss = { envFor = null },
            onSave = {
                vm.saveNodeEnv(project, it)
                envFor = null
            }
        )
    }

    renameFor?.let { old ->
        PromptDialog(
            title = "Rename project",
            label = "New name",
            initial = old,
            confirmText = "Rename",
            validator = { if (it == old) "Choose a different name" else nameError(it) },
            onConfirm = {
                vm.rename(old, it.trim())
                renameFor = null
            },
            onDismiss = { renameFor = null }
        )
    }

    duplicateFor?.let { src ->
        PromptDialog(
            title = "Duplicate project",
            label = "Name of the copy",
            initial = "$src-copy",
            confirmText = "Duplicate",
            validator = { nameError(it) },
            onConfirm = {
                vm.duplicate(src, it.trim())
                duplicateFor = null
            },
            onDismiss = { duplicateFor = null }
        )
    }

    deleteFor?.let { name ->
        ConfirmDialog(
            title = "Delete \"$name\"?",
            message = "All files in this project will be permanently deleted. Existing backups are kept.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                vm.delete(name)
                deleteFor = null
            },
            onDismiss = { deleteFor = null }
        )
    }
}

@Composable
private fun ProjectCard(
    info: ProjectInfo,
    isActive: Boolean,
    isPrimary: Boolean,
    effPort: Int,
    hostedState: ProcessState,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onPreview: () -> Unit,
    onSetActive: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onAiFix: () -> Unit
) {
    val c = Ix.c
    val cfg = info.config
    var menu by remember { mutableStateOf(false) }
    IxCard(onClick = if (selecting) onToggle else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            if (hostedState != ProcessState.STOPPED) {
                StatusDot(hostStatusColor(hostedState), pulse = hostedState == ProcessState.RUNNING)
                Spacer(Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(cfg.name, color = c.text, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StatusPill(
                        when (cfg.runtime) {
                            RuntimeType.STATIC -> "STATIC"
                            RuntimeType.NODE -> "NODE"
                            RuntimeType.SERVER -> "SERVER"
                        }, c.accent
                    )
                    if (isActive) StatusPill("ACTIVE", c.ok)
                }
            }
            Column {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = c.soft)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (hostedState == ProcessState.STOPPED) {
                        DropdownMenuItem(text = { Text("Start hosting", color = c.text) }, onClick = { menu = false; onStart() })
                    } else {
                        DropdownMenuItem(text = { Text("Stop hosting", color = c.text) }, onClick = { menu = false; onStop() })
                    }
                    DropdownMenuItem(text = { Text("Set active", color = c.text) }, onClick = { menu = false; onSetActive() })
                    DropdownMenuItem(text = { Text("Rename", color = c.text) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate", color = c.text) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export ZIP", color = c.text) }, onClick = { menu = false; onExport() })
                    if (cfg.runtime == RuntimeType.SERVER) {
                        DropdownMenuItem(
                            text = { Text("✦ AI Fix (Groq)", color = c.text) },
                            onClick = { menu = false; onAiFix() }
                        )
                    }
                    DropdownMenuItem(text = { Text("Delete", color = c.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Size", Fmt.bytes(info.sizeBytes))
        KeyValueRow("Files", info.fileCount.toString())
        KeyValueRow("Modified", Fmt.date(info.lastModified))
        KeyValueRow("Hosting", if (hostedState == ProcessState.STOPPED) "Idle" else hostStatusText(hostedState), valueColor = hostStatusColor(hostedState))
        KeyValueRow(
            "Port",
            if (isPrimary && hostedState != ProcessState.STOPPED) "$effPort (primary — tunnel target)" else effPort.toString(),
            mono = true
        )
        KeyValueRow("Domain", cfg.domain.ifBlank { "Not set" }, valueColor = if (cfg.domain.isBlank()) c.soft else c.text)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IxButton("Settings", onSettings, icon = Icons.Rounded.Settings, kind = BtnKind.Secondary, modifier = Modifier.weight(1f))
            IxButton(
                "Preview",
                onPreview,
                icon = Icons.Rounded.OpenInBrowser,
                kind = BtnKind.Secondary,
                enabled = hostedState == ProcessState.RUNNING,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, RuntimeType) -> Unit) {
    val c = Ix.c
    var name by remember { mutableStateOf("") }
    var runtime by remember { mutableStateOf(RuntimeType.STATIC) }
    val err = nameError(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IxTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Project name",
                    isError = name.isNotEmpty() && err != null,
                    supportingText = if (name.isNotEmpty()) err else null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxChip("Static site", runtime == RuntimeType.STATIC, { runtime = RuntimeType.STATIC })
                    IxChip("Node.js", runtime == RuntimeType.NODE, { runtime = RuntimeType.NODE })
                    IxChip("Server", runtime == RuntimeType.SERVER, { runtime = RuntimeType.SERVER })
                }
                when (runtime) {
                    RuntimeType.NODE -> Text(
                        "Node projects need an Android-compatible ARM64 Node runtime (Settings → Node Runtime).",
                        color = c.soft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    RuntimeType.SERVER -> Text(
                        "Server projects run on the built-in Node.js — no download needed. " +
                            "One tap starts all servers together.",
                        color = c.soft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> {}
                }
            }
        },
        confirmButton = { IxButton("Create", onClick = { onCreate(name.trim(), runtime) }, enabled = name.isNotBlank() && err == null) },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )
}

@Composable
private fun CreateAdminDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val c = Ix.c
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val userErr = if (username.isNotEmpty() && username.length !in 3..64) "3–64 characters" else null
    val passErr = if (password.isNotEmpty() && password.length < 12) "At least 12 characters" else null
    val valid = username.length in 3..64 && password.length >= 12
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        textContentColor = c.soft,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("Create admin account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Runs: node scripts/create-admin.js — the password is passed to the process only and is never stored.",
                    color = c.soft,
                    style = MaterialTheme.typography.bodySmall
                )
                IxTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = "Username",
                    isError = userErr != null,
                    supportingText = userErr
                )
                SecretField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    hasSaved = false,
                    supportingText = passErr ?: "Minimum 12 characters"
                )
            }
        },
        confirmButton = { IxButton("Create", onClick = { onConfirm(username, password) }, enabled = valid) },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )
}

@Composable
private fun ImportDialog(
    stage: ImportStage,
    existing: List<String>,
    onCancel: () -> Unit,
    onImport: (String) -> Unit,
    busy: Boolean
) {
    val c = Ix.c
    val info = stage.info
    var name by remember(stage) { mutableStateOf(stage.suggestedName) }
    val err = nameError(name) ?: if (existing.contains(name)) "A project with this name already exists" else null
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = c.card,
        titleContentColor = c.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("Import ZIP") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KeyValueRow("File", stage.sourceName)
                KeyValueRow("ZIP size", Fmt.bytes(info.compressedBytes))
                KeyValueRow("File count", info.fileCount.toString())
                KeyValueRow("Extracted", "≈ " + Fmt.bytes(info.uncompressedBytes))
                KeyValueRow(
                    "index.html",
                    if (info.hasIndexHtml) "Found" else "Not found",
                    valueColor = if (info.hasIndexHtml) c.ok else c.warn
                )
                stage.detection?.let { d ->
                    KeyValueRow("Type", "Server (Node.js) — auto-detected", valueColor = c.ok)
                    KeyValueRow("Entry", d.entryGuess, mono = true)
                    KeyValueRow(
                        "node_modules",
                        if (d.hasNodeModules) "Included" else "MISSING",
                        valueColor = if (d.hasNodeModules) c.ok else c.warn
                    )
                    if (!d.hasNodeModules) {
                        Text(
                            "This ZIP has no node_modules — the server will fail to start until its npm " +
                                "dependencies are included. Re-export with node_modules and re-import.",
                            color = c.warn,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (d.skippedSecrets.isNotEmpty()) {
                        Text(
                            "Secrets skipped on import: ${d.skippedSecrets.joinToString(", ")} — add keys " +
                                "in Project → Settings → Environment secrets instead.",
                            color = c.warn,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (info.stripPrefix.isNotEmpty()) {
                    Text("The top-level folder \"${info.stripPrefix.trimEnd('/')}\" will be flattened.", color = c.soft, style = MaterialTheme.typography.bodySmall)
                }
                if (info.otherTypes.isNotEmpty()) {
                    Text(
                        "Also contains other file types (${info.otherTypes.take(8).joinToString(", ")}). They are kept as-is.",
                        color = c.soft,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (info.error != null) {
                    Text(info.error ?: "", color = c.danger, style = MaterialTheme.typography.bodyMedium)
                }
                if (info.unsafeEntries.isNotEmpty()) {
                    Text(
                        "Rejected: this ZIP contains unsafe paths (path traversal / absolute paths), e.g. ${info.unsafeEntries.first()}",
                        color = c.danger,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                IxTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Project name",
                    isError = err != null,
                    supportingText = err
                )
            }
        },
        confirmButton = {
            IxButton("Import", onClick = { onImport(name.trim()) }, enabled = info.isSafe && err == null && !busy, loading = busy)
        },
        dismissButton = { IxButton("Cancel", onCancel, kind = BtnKind.Ghost, enabled = !busy) }
    )
}

@Composable
private fun ConfigDialog(
    cfg: ProjectConfig,
    nodeInstalled: Boolean,
    serverAvailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProjectConfig) -> Unit,
    onEnv: () -> Unit,
    onCreateAdmin: (String, String) -> Unit,
    onRunScript: (String) -> Unit
) {
    val c = Ix.c
    var runtime by remember { mutableStateOf(cfg.runtime) }
    var port by remember { mutableStateOf(cfg.port.toString()) }
    var rootDir by remember { mutableStateOf(cfg.rootDir) }
    var spa by remember { mutableStateOf(cfg.spa) }
    var domain by remember { mutableStateOf(cfg.domain) }
    var cf by remember { mutableStateOf(cfg.cloudflareEnabled) }
    var autoStart by remember { mutableStateOf(cfg.autoStart) }
    var autoRestart by remember { mutableStateOf(cfg.autoRestart) }
    var entry by remember { mutableStateOf(cfg.entryFile) }
    var args by remember { mutableStateOf(cfg.nodeArgs) }
    var showAdmin by remember { mutableStateOf(false) }
    var oneOff by remember { mutableStateOf("") }

    val portNum = port.toIntOrNull()
    val portErr = if (portNum == null || portNum !in 1024..65535) "Use a port between 1024 and 65535" else null
    val domainClean = cleanDomain(domain)
    val domainErr = if (domainClean.isNotEmpty() && !isValidHostname(domainClean)) "Enter a valid hostname, e.g. example.com" else null
    val rootErr = if (rootDir.contains("..") || rootDir.startsWith("/")) "Must be a relative folder inside the project" else null
    val entryErr = if ((runtime == RuntimeType.NODE || runtime == RuntimeType.SERVER) &&
        (entry.isBlank() || entry.contains("..") || entry.startsWith("/"))
    ) "Relative file inside the project" else null
    val valid = portErr == null && domainErr == null && rootErr == null && entryErr == null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("${cfg.name} · settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Runtime", color = c.soft, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxChip("STATIC", runtime == RuntimeType.STATIC, { runtime = RuntimeType.STATIC })
                    IxChip("NODE", runtime == RuntimeType.NODE, { runtime = RuntimeType.NODE })
                    IxChip("SERVER", runtime == RuntimeType.SERVER, { runtime = RuntimeType.SERVER })
                }
                if (runtime == RuntimeType.NODE && !nodeInstalled) {
                    Text("Node runtime not installed", color = c.warn, style = MaterialTheme.typography.bodySmall)
                }
                if (runtime == RuntimeType.SERVER && !serverAvailable) {
                    Text(
                        "Built-in Node.js is not bundled in this build — server projects cannot start",
                        color = c.warn,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IxTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", isError = portErr != null, supportingText = portErr ?: "Used when this project runs alongside others. The ACTIVE project always uses the primary port (Settings).", keyboardType = KeyboardType.Number)
                if (runtime == RuntimeType.STATIC) {
                    IxTextField(rootDir, { rootDir = it.trim() }, "Root directory (optional)", placeholder = "e.g. dist", isError = rootErr != null, supportingText = rootErr ?: "Sub-folder that is served. Empty = project root.")
                    SwitchRow("SPA mode", spa, { spa = it }, subtitle = "Unknown routes fall back to index.html")
                } else if (runtime == RuntimeType.SERVER) {
                    IxTextField(entry, { entry = it.trim() }, "Entry file", isError = entryErr != null, supportingText = entryErr ?: "Server file to run, e.g. src/server.js. PORT is set automatically.")
                    IxButton("Environment secrets…", onClick = onEnv, kind = BtnKind.Secondary, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Runs on the built-in Node.js in a shared process — no download needed, one tap " +
                            "starts all servers together. SESSION_SECRET is auto-generated; add API keys " +
                            "(e.g. RESEND_API_KEY) in Environment secrets above. The ZIP must include node_modules.",
                        color = c.soft,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    IxTextField(entry, { entry = it.trim() }, "Entry file", isError = entryErr != null, supportingText = entryErr)
                    IxTextField(args, { args = it }, "Arguments (optional)")
                    IxButton("Environment secrets…", onClick = onEnv, kind = BtnKind.Secondary, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Backend setup — the AyX Group backend needs SESSION_SECRET (and optional DB_PATH) in Environment secrets above.",
                        color = c.soft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IxButton("Create admin account…", onClick = { showAdmin = true }, kind = BtnKind.Secondary, modifier = Modifier.fillMaxWidth())
                    IxTextField(
                        value = oneOff,
                        onValueChange = { oneOff = it },
                        label = "One-off command",
                        placeholder = "scripts/migrate.js --flag",
                        monospace = true
                    )
                    IxButton(
                        "Run one-off command",
                        onClick = { onRunScript(oneOff.trim()) },
                        kind = BtnKind.Secondary,
                        enabled = oneOff.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                IxTextField(domain, { domain = it }, "Custom domain", placeholder = "imayx.in", isError = domainErr != null, supportingText = domainErr ?: "Must also be routed to this tunnel in Cloudflare.")
                SwitchRow("Cloudflare enabled", cf, { cf = it })
                SwitchRow("Auto start", autoStart, { autoStart = it }, subtitle = "Start this project after reboot (also enable it in Settings)")
                SwitchRow("Auto restart", autoRestart, { autoRestart = it }, subtitle = "Restart after a crash (max 3 times in 10 minutes)")
            }
        },
        confirmButton = {
            IxButton(
                "Save",
                onClick = {
                    onSave(
                        cfg.copy(
                            runtime = runtime,
                            port = portNum ?: cfg.port,
                            rootDir = rootDir.trim('/'),
                            spa = spa,
                            domain = domainClean,
                            cloudflareEnabled = cf,
                            autoStart = autoStart,
                            autoRestart = autoRestart,
                            entryFile = entry.ifBlank { "server.js" },
                            nodeArgs = args.trim()
                        )
                    )
                },
                enabled = valid
            )
        },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )

    if (showAdmin) {
        CreateAdminDialog(
            onDismiss = { showAdmin = false },
            onConfirm = { u, p ->
                showAdmin = false
                onCreateAdmin(u, p)
            }
        )
    }
}

@Composable
private fun EnvDialog(initial: Map<String, String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val c = Ix.c
    var text by remember { mutableStateOf(initial.entries.joinToString("\n") { it.key + "=" + it.value }) }
    val keyRx = remember { Regex("^[A-Za-z_][A-Za-z0-9_]*$") }
    fun parse(): Pair<Map<String, String>, String?> {
        val map = LinkedHashMap<String, String>()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return map to "Line ${i + 1}: expected KEY=VALUE"
            val k = line.substring(0, eq).trim()
            if (!keyRx.matches(k)) return map to "Line ${i + 1}: invalid variable name"
            map[k] = line.substring(eq + 1)
        }
        return map to null
    }
    val parsed = parse()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("Environment secrets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One KEY=VALUE per line. Stored encrypted with the Android Keystore, never logged, never included in backups or exports.",
                    color = c.soft,
                    style = MaterialTheme.typography.bodySmall
                )
                IxTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Variables",
                    singleLine = false,
                    monospace = true,
                    isError = parsed.second != null,
                    supportingText = parsed.second
                )
            }
        },
        confirmButton = { IxButton("Save", onClick = { onSave(parsed.first) }, enabled = parsed.second == null) },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )
}
