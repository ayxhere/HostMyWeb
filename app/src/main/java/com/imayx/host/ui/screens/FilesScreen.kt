package com.imayx.host.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.files.FileItem
import com.imayx.host.files.SortKey
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.ConfirmDialog
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.ErrorState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.LoadingState
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.PromptDialog
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.EditorViewModel
import com.imayx.host.ui.vm.FilesViewModel
import com.imayx.host.utils.Fmt
import com.imayx.host.utils.PathUtil

private fun entryError(name: String): String? =
    if (PathUtil.isValidEntryName(name)) null else "Name cannot contain / or \\"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    onOpenEditor: () -> Unit,
    vm: FilesViewModel = viewModel(),
    editor: EditorViewModel = viewModel()
) {
    val c = Ix.c
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clip by vm.clip.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    var actionsFor by remember { mutableStateOf<FileItem?>(null) }
    var renameFor by remember { mutableStateOf<FileItem?>(null) }
    var deleteFor by remember { mutableStateOf<FileItem?>(null) }
    var newKind by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var downloadItem by remember { mutableStateOf<FileItem?>(null) }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = downloadItem
        if (uri != null && item != null) vm.exportTo(item, uri)
        downloadItem = null
    }

    fun openItem(item: FileItem) {
        if (item.isDir) {
            vm.openFolder(item.relPath)
        } else if (editor.isEditable(item.name)) {
            val p = ui.project ?: return
            editor.open(p, item.relPath)
            onOpenEditor()
        } else {
            snack("\"${item.name}\" can't be edited here. Long-press for Download or Share.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            "Files",
            subtitle = if (ui.project == null) "No project" else ui.project + "/" + ui.path,
            actions = {
                IconButton(onClick = { vm.toggleSearch() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = if (ui.searching) c.accent else c.text)
                }
                Column {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = "Sort", tint = c.text)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (k in SortKey.values()) {
                            val mark = if (ui.sort == k) (if (ui.ascending) "  ↑" else "  ↓") else ""
                            DropdownMenuItem(
                                text = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() } + mark, color = c.text) },
                                onClick = {
                                    sortMenu = false
                                    vm.setSort(k)
                                }
                            )
                        }
                    }
                }
                Column {
                    IconButton(onClick = { addMenu = true }, enabled = ui.project != null) {
                        Icon(Icons.Rounded.Add, contentDescription = "New", tint = c.text)
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("New folder", color = c.text) }, onClick = { addMenu = false; newKind = "folder" })
                        DropdownMenuItem(text = { Text("New file", color = c.text) }, onClick = { addMenu = false; newKind = "file" })
                    }
                }
            }
        )

        if (ui.projects.size > 1) {
            IxSelect(
                label = "Project",
                selectedLabel = ui.project ?: "",
                options = ui.projects.map { it to it },
                onSelect = { vm.selectProject(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        if (ui.searching) {
            IxTextField(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                label = "Search in this folder",
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        clip?.let { cl ->
            IxCard(modifier = Modifier.padding(horizontal = 16.dp), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (cl.cut) "Move: " else "Copy: ") + cl.name,
                        color = c.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IxButton("Paste here", { vm.paste() }, icon = Icons.Rounded.ContentPaste)
                    Spacer(Modifier.width(6.dp))
                    IxButton("Cancel", { vm.clearClip() }, kind = BtnKind.Ghost)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (ui.path.isNotEmpty() && !ui.searching) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IxButton("Up", { vm.up() }, icon = Icons.Rounded.ArrowUpward, kind = BtnKind.Secondary)
            }
            Spacer(Modifier.height(8.dp))
        }

        val list: List<FileItem> = if (ui.searching && ui.query.isNotBlank()) ui.results else ui.items
        when {
            ui.loading -> LoadingState("Loading files…")
            ui.project == null -> EmptyState(
                icon = Icons.Rounded.Folder,
                title = "No websites hosted yet",
                subtitle = "Import your first ZIP in the Projects tab"
            )
            ui.error != null -> ErrorState("Cannot read folder", ui.error ?: "", onRetry = { vm.refreshFolder() })
            list.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Folder,
                title = if (ui.searching && ui.query.isNotBlank()) "No matches" else "This folder is empty",
                subtitle = if (ui.searching && ui.query.isNotBlank()) "Try a different search" else "Create a file or folder with the + button"
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.relPath }) { item ->
                    FileRow(
                        item = item,
                        showPath = ui.searching && ui.query.isNotBlank(),
                        onClick = { openItem(item) },
                        onLongClick = { actionsFor = item }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    actionsFor?.let { item ->
        val c2 = Ix.c
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            containerColor = c2.card,
            titleContentColor = c2.text,
            shape = RoundedCornerShape(24.dp),
            title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (!item.isDir && editor.isEditable(item.name)) {
                        ActionRow(Icons.Rounded.Code, "Open in editor") { actionsFor = null; openItem(item) }
                    }
                    ActionRow(Icons.Rounded.Edit, "Rename") { actionsFor = null; renameFor = item }
                    ActionRow(Icons.Rounded.ContentCopy, "Copy") { actionsFor = null; vm.copy(item) }
                    ActionRow(Icons.Rounded.DriveFileMove, "Move") { actionsFor = null; vm.cut(item) }
                    ActionRow(Icons.Rounded.FileCopy, "Duplicate") { actionsFor = null; vm.duplicate(item) }
                    ActionRow(Icons.Rounded.Download, "Download") {
                        actionsFor = null
                        downloadItem = item
                        downloadLauncher.launch(if (item.isDir) item.name + ".zip" else item.name)
                    }
                    ActionRow(Icons.Rounded.Share, "Share") {
                        actionsFor = null
                        vm.shareIntent(item)?.let {
                            context.startActivity(Intent.createChooser(it, "Share " + item.name))
                        }
                    }
                    ActionRow(Icons.Rounded.Delete, "Delete", danger = true) { actionsFor = null; deleteFor = item }
                }
            },
            confirmButton = {},
            dismissButton = { IxButton("Close", { actionsFor = null }, kind = BtnKind.Ghost) }
        )
    }

    renameFor?.let { item ->
        PromptDialog(
            title = "Rename",
            label = "New name",
            initial = item.name,
            confirmText = "Rename",
            validator = { if (it == item.name) "Choose a different name" else entryError(it) },
            onConfirm = {
                vm.rename(item, it.trim())
                renameFor = null
            },
            onDismiss = { renameFor = null }
        )
    }

    deleteFor?.let { item ->
        ConfirmDialog(
            title = "Delete \"${item.name}\"?",
            message = if (item.isDir) "This folder and everything inside it will be permanently deleted." else "This file will be permanently deleted.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                vm.delete(item)
                deleteFor = null
            },
            onDismiss = { deleteFor = null }
        )
    }

    newKind?.let { kind ->
        PromptDialog(
            title = if (kind == "folder") "New folder" else "New file",
            label = "Name",
            initial = "",
            confirmText = "Create",
            validator = { entryError(it) },
            onConfirm = {
                if (kind == "folder") vm.createFolder(it.trim()) else vm.createFile(it.trim())
                newKind = null
            },
            onDismiss = { newKind = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileItem, showPath: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Ix.c
    val shape = RoundedCornerShape(16.dp)
    val icon: ImageVector = if (item.isDir) Icons.Rounded.Folder else Icons.Rounded.Description
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card, shape)
            .border(1.dp, c.border, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (item.isDir) c.accent else c.soft, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = (if (item.isDir) "Folder" else Fmt.bytes(item.size)) + " · " + Fmt.date(item.modified)
            Text(if (showPath) item.relPath else meta, color = c.soft, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Ix.c
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (danger) c.danger else c.text, style = MaterialTheme.typography.bodyLarge)
    }
}
