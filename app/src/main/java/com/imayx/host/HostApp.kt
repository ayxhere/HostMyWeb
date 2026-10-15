package com.imayx.host

import android.app.Application
import android.content.Context
import com.imayx.host.backup.BackupManager
import com.imayx.host.cloudflare.CloudflareApi
import com.imayx.host.cloudflare.CloudflareManager
import com.imayx.host.cloudflare.TunnelController
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProjectStore
import com.imayx.host.domain.HostController
import com.imayx.host.projects.ProjectManager
import com.imayx.host.runtime.NodeInstaller
import com.imayx.host.runtime.ProcessRegistry
import com.imayx.host.runtime.ResourceMonitor
import com.imayx.host.runtime.RuntimeManager
import com.imayx.host.security.AppLock
import com.imayx.host.security.SecretStore

/** Hand-wired object graph (no DI framework: keeps the on-device build simple and fast). */
class AppGraph(val app: Application) {
    val settings = AppSettings(app)
    val secrets = SecretStore(app)
    val appLock = AppLock(app)
    val registry = ProcessRegistry(app)
    val projectStore = ProjectStore(app)
    val projects = ProjectManager(app, projectStore, settings, secrets)
    val backups = BackupManager(app, projects)
    val cloudflare = CloudflareManager(app, settings)
    val tunnel = TunnelController(app, cloudflare, registry, settings)
    val cloudflareApi = CloudflareApi { secrets.get(SecretStore.KEY_API_TOKEN) }
    val nodeInstaller = NodeInstaller(app)
    val runtimes = RuntimeManager(app, projectStore, secrets, nodeInstaller, registry)
    val host = HostController(app, settings, projectStore, runtimes, tunnel, secrets, backups)
    val monitor = ResourceMonitor(app)
}

class HostApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Keystore + /proc work off the main thread.
        Thread({
            try {
                graph.secrets.registerAllForRedaction()
            } catch (_: Exception) {
            }
            try {
                graph.registry.reapStale()
            } catch (_: Exception) {
            }
            LogBus.i(LogCategory.SYSTEM, "IMAYX Host started")
        }, "ix-init").start()
    }
}

val Context.graph: AppGraph
    get() = (applicationContext as HostApp).graph
