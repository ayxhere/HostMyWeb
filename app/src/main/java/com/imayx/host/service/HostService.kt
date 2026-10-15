package com.imayx.host.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.imayx.host.MainActivity
import com.imayx.host.R
import com.imayx.host.data.HostState
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.data.TunnelState
import com.imayx.host.data.TunnelStatus
import com.imayx.host.graph
import com.imayx.host.node.AyxBackendRuntime
import com.imayx.host.node.ServerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps hosting alive in the background. The notification never contains the
 * tunnel token or any other secret.
 */
class HostService : Service() {

    companion object {
        const val CHANNEL_ID = "imayx_host_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.imayx.host.action.START"
        const val ACTION_STOP = "com.imayx.host.action.STOP"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_PROJECTS = "projects"
        const val EXTRA_BOOT = "boot"

        fun start(context: Context, project: String? = null, boot: Boolean = false) {
            val i = Intent(context, HostService::class.java).setAction(ACTION_START)
            if (project != null) i.putExtra(EXTRA_PROJECT, project)
            if (boot) i.putExtra(EXTRA_BOOT, true)
            ContextCompat.startForegroundService(context, i)
        }

        /** Starts several projects at once (multi-start). */
        fun startMany(context: Context, projects: List<String>) {
            val i = Intent(context, HostService::class.java).setAction(ACTION_START)
            i.putExtra(EXTRA_PROJECT, projects.first())
            i.putExtra(EXTRA_PROJECTS, projects.toTypedArray())
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HostService::class.java).setAction(ACTION_STOP))
        }

        /** True when the OS is told not to kill this app in the background. */
        fun isBatteryUnrestricted(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /** Intent that opens the system "don't optimize this app" prompt. */
        fun batteryUnrestrictedIntent(context: Context): Intent =
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var watchdog: Job? = null
    private var seenActive = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = graph
        when (intent?.action) {
            ACTION_STOP -> {
                promote(HostState(), TunnelState())
                g.host.stop()
                observeUntilStopped(expectStop = true)
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null && !g.settings.hostingWanted) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                promote(HostState(state = ProcessState.STARTING), TunnelState())
                val boot = intent?.getBooleanExtra(EXTRA_BOOT, false) == true
                val project = intent?.getStringExtra(EXTRA_PROJECT)
                    ?: if (boot) g.host.bootProject() else null
                val many = intent?.getStringArrayExtra(EXTRA_PROJECTS)
                if (many != null && many.size > 1) g.host.startMany(many.toList()) else g.host.start(project)
                // Embedded AyX Group backend (Node.js + SQLite, in-app). It lives as long as
                // this service does; AyxBackendRuntime is idempotent per OS process.
                if (g.settings.ayxBackendEnabled) {
                    AyxBackendRuntime.ensureStarted(this)
                }
                observeUntilStopped(expectStop = false)
                startWatchdog()
            }
        }
        return START_STICKY
    }

    private fun promote(host: HostState, tunnel: TunnelState) {
        val n = buildNotification(host, tunnel)
        try {
            startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) {
            // Foreground start not allowed right now; hosting still proceeds while the process lives.
        }
    }

    private fun observeUntilStopped(expectStop: Boolean) {
        observer?.cancel()
        seenActive = expectStop
        val g = graph
        observer = scope.launch {
            combine(g.host.state, g.tunnel.state) { h, t -> h to t }.collect { (h, t) ->
                when (h.state) {
                    ProcessState.STARTING, ProcessState.RUNNING -> {
                        seenActive = true
                        postNotification(buildNotification(h, t))
                    }
                    ProcessState.STOPPED -> {
                        if (seenActive) finish(removeNotification = true)
                    }
                    ProcessState.FAILED -> {
                        postNotification(buildNotification(h, t))
                        finish(removeNotification = false)
                    }
                }
            }
        }
    }

    private fun finish(removeNotification: Boolean) {
        observer?.cancel()
        watchdog?.cancel()
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        stopSelf()
    }

    /**
     * 24/7 self-heal. Every 60s (cheap: a couple of socket connects, no polling loops),
     * restarts anything that died while the user still wants hosting:
     *  - host process STOPPED/FAILED → start hosting again
     *  - node server ports refusing connections → re-sync the `:node_server` process
     * The tunnel has its own retry inside TunnelController; a dead tunnel process is
     * covered by the host restart above.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        val g = graph
        watchdog = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(60_000)
                try {
                    if (!g.settings.hostingWanted) continue
                    val st = g.host.state.value.state
                    if (st == ProcessState.STOPPED || st == ProcessState.FAILED) {
                        LogBus.w(LogCategory.SYSTEM, "Watchdog: hosting wanted but state=$st — restarting")
                        g.host.start(g.host.bootProject())
                        continue
                    }
                    if (st == ProcessState.RUNNING) {
                        val ports = ServerRuntime.wantedPorts(applicationContext)
                        val dead = ports.any { !portAccepts(it) }
                        if (dead) {
                            LogBus.w(LogCategory.NODE, "Watchdog: a node server port is refusing — restarting server runtime")
                            ServerHostService.sync(applicationContext)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogBus.w(LogCategory.SYSTEM, "Watchdog error: ${e.message}")
                }
            }
        }
    }

    private fun portAccepts(port: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun postNotification(n: android.app.Notification) {
        if (!graph.settings.notificationsEnabled) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Hosting status", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while IMAYX Host is serving a project"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(h: HostState, t: TunnelState): android.app.Notification {
        val online = h.state == ProcessState.RUNNING
        val statusText = when (h.state) {
            ProcessState.RUNNING -> "ONLINE"
            ProcessState.STARTING -> "STARTING"
            ProcessState.FAILED -> "FAILED"
            ProcessState.STOPPED -> "OFFLINE"
        }
        val project = (h.project ?: graph.settings.activeProject.ifBlank { "-" }) +
            (if (h.extras.isNotEmpty()) " +${h.extras.size}" else "")
        val tunnelLine = when (t.status) {
            TunnelStatus.CONNECTED -> "Cloudflare: connected"
            TunnelStatus.STARTING -> "Cloudflare: connecting…"
            TunnelStatus.FAILED -> "Cloudflare: failed"
            TunnelStatus.DISCONNECTED -> ""
        }
        val big = buildString {
            append("Hosting: ").append(statusText).append('\n')
            append("Project: ").append(project).append('\n')
            append("Port: ").append(h.port)
            if (tunnelLine.isNotEmpty()) append('\n').append(tunnelLine)
            if (h.state == ProcessState.FAILED && h.error != null) append('\n').append(h.error.take(160))
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_host)
            .setContentTitle("IMAYX Host")
            .setContentText("Hosting: $statusText · $project · :${h.port}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(openIntent)
            .setOngoing(online || h.state == ProcessState.STARTING)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Open App", openIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val g = context.graph
        // Only when the user explicitly enabled "Start hosting after reboot".
        if (g.settings.startOnBoot) {
            HostService.start(context, boot = true)
        }
    }
}
