package com.imayx.host.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Rounded.Dashboard),
    Projects("Projects", Icons.Rounded.Language),
    Files("Files", Icons.Rounded.Folder),
    Editor("Editor", Icons.Rounded.Code),
    Cloudflare("Cloudflare", Icons.Rounded.Cloud),
    PentAGI("PentAGI", Icons.Rounded.Security),
    Logs("Logs", Icons.Rounded.Terminal),
    Backups("Backups", Icons.Rounded.Backup),
    Settings("Settings", Icons.Rounded.Settings)
}
