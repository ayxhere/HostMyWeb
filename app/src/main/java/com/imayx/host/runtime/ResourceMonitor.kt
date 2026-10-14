package com.imayx.host.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.imayx.host.utils.PathUtil
import java.io.File

data class ResourceSnapshot(
    /** CPU used by this app + hosted child processes, as % of total device CPU capacity. Null on first sample. */
    val cpuPercent: Float?,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val appRamBytes: Long,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val projectBytes: Long?,
    /** Network bytes/s for this app's UID, or null if the platform does not report it. */
    val rxPerSec: Long?,
    val txPerSec: Long?
)

/**
 * Only reports what Android actually lets an unprivileged app measure.
 * System-wide CPU (/proc/stat) is not readable since Android 8, so CPU here is explicitly
 * "this app and its child processes" — anything not measurable is returned as null ("Unavailable" in the UI).
 */
class ResourceMonitor(private val context: Context) {

    private var lastWall = 0L
    private var lastCpuMs = 0L
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastNetAt = 0L
    private var cachedProjectBytes: Long? = null
    private var cachedProjectAt = 0L
    private var cachedProjectDir: String? = null
    /** Debug.getPss() is expensive — refresh the app-RAM figure at most every 15s. */
    private var cachedAppRam: Long = 0L
    private var cachedAppRamAt: Long = 0L
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @Synchronized
    fun sample(childPids: List<Int>, projectDir: File?): ResourceSnapshot {
        val now = SystemClock.elapsedRealtime()

        var cpuMs = Process.getElapsedCpuTime()
        for (pid in childPids) {
            ProcessUtil.cpuMillis(pid)?.let { cpuMs += it }
        }
        val cpu: Float? = if (lastWall > 0 && now > lastWall) {
            val d = (cpuMs - lastCpuMs).coerceAtLeast(0)
            ((d.toDouble() / (now - lastWall).toDouble()) / cores * 100.0).toFloat().coerceIn(0f, 100f)
        } else null
        lastWall = now
        lastCpuMs = cpuMs

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramUsed = (mi.totalMem - mi.availMem).coerceAtLeast(0)
        val appRam = if (cachedAppRamAt == 0L || now - cachedAppRamAt > 15_000) {
            cachedAppRam = try {
                Debug.getPss() * 1024
            } catch (_: Throwable) {
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            }
            cachedAppRamAt = now
            cachedAppRam
        } else {
            cachedAppRam
        }

        val stat = StatFs(context.filesDir.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong

        var projectBytes: Long? = null
        if (projectDir != null) {
            val key = projectDir.path
            if (cachedProjectDir != key || now - cachedProjectAt > 60_000) {
                cachedProjectBytes = if (projectDir.isDirectory) PathUtil.folderSize(projectDir) else 0L
                cachedProjectAt = now
                cachedProjectDir = key
            }
            projectBytes = cachedProjectBytes
        }

        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        var rxRate: Long? = null
        var txRate: Long? = null
        val prevRx = lastRx
        val prevTx = lastTx
        val prevAt = lastNetAt
        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
            if (prevRx >= 0 && prevAt > 0 && now > prevAt) {
                val secs = (now - prevAt) / 1000.0
                rxRate = ((rx - prevRx).coerceAtLeast(0) / secs).toLong()
                txRate = ((tx - prevTx).coerceAtLeast(0) / secs).toLong()
            }
            lastRx = rx
            lastTx = tx
            lastNetAt = now
        }

        return ResourceSnapshot(
            cpuPercent = cpu,
            ramUsedBytes = ramUsed,
            ramTotalBytes = mi.totalMem,
            appRamBytes = appRam,
            storageUsedBytes = (total - free).coerceAtLeast(0),
            storageTotalBytes = total,
            projectBytes = projectBytes,
            rxPerSec = rxRate,
            txPerSec = txRate
        )
    }
}
