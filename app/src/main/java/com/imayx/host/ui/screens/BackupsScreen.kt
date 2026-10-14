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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.backup.BackupInfo
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.ConfirmDialog
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.LoadingState
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.BackupsViewModel
import com.imayx.host.utils.Fmt

@Composable
fun BackupsScreen(vm: BackupsViewModel = viewModel()) {
    val c = Ix.c
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    var restoreFor by remember { mutableStateOf<BackupInfo?>(null) }
    var deleteFor by remember { mutableStateOf<BackupInfo?>(null) }
    var exportFor by remember { mutableStateOf<BackupInfo?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val b = exportFor
        if (uri != null && b != null) vm.export(b, uri)
        exportFor = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Backups", subtitle = "ZIP snapshots of your projects")
        if (ui.loading) {
            LoadingState()
            return@Column
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.projects.isEmpty()) {
                Text("Create a project first to back it up.", color = c.soft, style = MaterialTheme.typography.bodyMedium)
            } else {
                IxSelect("Project", ui.selected, ui.projects.map { it to it }, { vm.select(it) })
                IxButton(
                    "Create backup",
                    { vm.create() },
                    icon = Icons.Rounded.Backup,
                    loading = ui.busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Backups never contain tokens or secrets — those stay in the Android Keystore.", color = c.soft, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        if (ui.backups.isEmpty()) {
            EmptyState(Icons.Rounded.Backup, "No backups yet", "Create a backup to protect your project files.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ui.backups, key = { it.file.name }) { b ->
                    IxCard {
                        Text(b.project, color = c.text, style = MaterialTheme.typography.titleMedium)
                        Text("${Fmt.date(b.createdAt)} · ${Fmt.bytes(b.sizeBytes)}", color = c.soft, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IxButton("Restore", { restoreFor = b }, icon = Icons.Rounded.Restore, kind = BtnKind.Secondary, enabled = !ui.busy)
                            IxButton("Export", { exportFor = b; exporter.launch(b.file.name) }, icon = Icons.Rounded.FileDownload, kind = BtnKind.Secondary, enabled = !ui.busy)
                            IxButton("Delete", { deleteFor = b }, icon = Icons.Rounded.Delete, kind = BtnKind.Danger, enabled = !ui.busy)
                        }
                    }
                }
            }
        }
    }

    restoreFor?.let { b ->
        val exists = b.project in ui.projects
        ConfirmDialog(
            title = "Restore backup?",
            message = if (exists) "Project \"${b.project}\" will be replaced with this backup. Current files in it are lost."
            else "Project \"${b.project}\" will be recreated from this backup.",
            confirmText = "Restore",
            destructive = exists,
            onConfirm = { vm.restore(b, b.project, replace = true); restoreFor = null },
            onDismiss = { restoreFor = null }
        )
    }
    deleteFor?.let { b ->
        ConfirmDialog(
            title = "Delete backup?",
            message = b.file.name,
            confirmText = "Delete",
            destructive = true,
            onConfirm = { vm.delete(b); deleteFor = null },
            onDismiss = { deleteFor = null }
        )
    }
}
