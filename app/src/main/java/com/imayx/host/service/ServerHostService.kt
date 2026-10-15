package com.imayx.host.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.imayx.host.MainActivity
import com.imayx.host.R
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.node.NodeBridge
import com.imayx.host.node.ServerRuntime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the embedded Node.js runtime for SERVER projects in a dedicated
 * `:node_server` OS process (see AndroidManifest).
 *
 * `node::Start` may run only once per OS process; the main process already
 * spends its one call on the AyX backend + wish launcher, so user servers run
 * here instead. One Node instance serves every SERVER project (via the
 * generated `launcher.mjs`), which keeps idle CPU at ~0% and RAM to one V8 heap.
 *
 * Lifecycle: [HostController] writes `servers.json` + `launcher.mjs` and calls
 * [sync]; [stop] removes the manifest and kills this process (nothing else
 * lives here, so killing it is the clean way to stop node). START_STICKY +
 * the on-disk manifest let the system revive servers after a process kill.
 */
class ServerHostService : Service() {

    companion object {
        const val CHANNEL_ID = "imayx_server_runtime"
        const val NOTIFICATION_ID = 1002
        const val ACTION_SYNC = "com.imayx.host.action.SERVER_SYNC"

        /** (Re)start the `:node_server` process with the current manifest. */
        fun sync(context: Context) {
            val i = Intent(context, ServerHostService::class.java).setAction(ACTION_SYNC)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerHostService::class.java))
        }
    }

    /** Guarded: node::Start runs once per process — never again, even across service restarts. */
    private val nodeStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val names = ServerRuntime.wantedServers(this)
        if (names.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        promote(names)
        if (!NodeBridge.available) {
            LogBus.e(LogCategory.NODE, "Server runtime: embedded node not bundled — cannot start servers")
            stopSelf()
            return START_NOT_STICKY
        }
        if (nodeStarted.compareAndSet(false, true)) {
            val dir = ServerRuntime.serversDir(this)
            Thread({
                try {
                    ServerRuntime.applyNodeEnv(this)
                    LogBus.i(LogCategory.NODE, "Server runtime: node starting (${names.joinToString(", ")})")
                    val rc = NodeBridge.nativeStart(arrayOf("node", "launcher.mjs"), dir.absolutePath)
                    LogBus.w(LogCategory.NODE, "Server runtime: node exited rc=$rc")
                } catch (e: Throwable) {
                    LogBus.e(LogCategory.NODE, "Server runtime failed: ${e.message}")
                }
            }, "ix-node-servers").start()
        } else {
            LogBus.i(LogCategory.NODE, "Server runtime: already running in this process (${names.joinToString(", ")})")
        }
        // If the system kills this process, come back and re-read the manifest.
        return START_STICKY
    }

    override fun onDestroy() {
        // This process exists only for node — killing it is the reliable stop.
        // (When the whole app is dying this is a no-op; when the user stopped
        // hosting it ends the servers immediately instead of lingering.)
        Process.killProcess(Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(names: List<String>) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_host)
            .setContentTitle("IMAYX Host · Node servers")
            .setContentText("Running: ${names.joinToString(", ").take(120)}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Open App", openIntent)
            .build()
        try {
            startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Node servers", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while SERVER projects are running"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
