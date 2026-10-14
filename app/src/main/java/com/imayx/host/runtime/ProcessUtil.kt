package com.imayx.host.runtime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

object ProcessUtil {

    fun pidOf(p: Process): Int {
        try {
            val m = Process::class.java.getMethod("pid")
            val v = m.invoke(p)
            if (v is Long) return v.toInt()
            if (v is Int) return v
        } catch (_: Throwable) {
        }
        try {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            return f.getInt(p)
        } catch (_: Throwable) {
        }
        return -1
    }

    private fun parentMap(): Map<Int, Int> {
        val parents = HashMap<Int, Int>()
        val dirs = File("/proc").listFiles() ?: return parents
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val stat = try {
                File(d, "stat").readText()
            } catch (_: Exception) {
                continue
            }
            val rp = stat.lastIndexOf(')')
            if (rp < 0 || rp + 2 >= stat.length) continue
            val fields = stat.substring(rp + 2).split(' ')
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
            parents[pid] = ppid
        }
        return parents
    }

    fun descendants(root: Int): List<Int> {
        if (root <= 0) return emptyList()
        val parents = parentMap()
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((child, parent) in parents) {
                if (parent == cur && child != root && !result.contains(child)) {
                    result.add(child)
                    queue.add(child)
                }
            }
        }
        return result
    }

    private fun signal(pid: Int, sig: Int) {
        try {
            Os.kill(pid, sig)
        } catch (_: Throwable) {
        }
    }

    fun isAlive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").exists()

    /** SIGTERM the whole tree, wait, then SIGKILL whatever is left. Prevents orphaned child processes. */
    fun killTree(pid: Int, graceMs: Long = 2500) {
        if (pid <= 0) return
        val tree = descendants(pid) + pid
        for (p in tree.reversed()) signal(p, OsConstants.SIGTERM)
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline && tree.any { isAlive(it) }) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        for (p in tree.reversed()) if (isAlive(p)) signal(p, OsConstants.SIGKILL)
    }

    /** CPU time (ms) consumed so far by [pid], read from /proc. Null if unreadable. */
    fun cpuMillis(pid: Int): Long? {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val rp = stat.lastIndexOf(')')
            val fields = stat.substring(rp + 2).split(' ')
            val utime = fields[11].toLong()
            val stime = fields[12].toLong()
            val hz = try {
                Os.sysconf(OsConstants._SC_CLK_TCK)
            } catch (_: Throwable) {
                100L
            }
            (utime + stime) * 1000L / (if (hz > 0) hz else 100L)
        } catch (_: Exception) {
            null
        }
    }

    fun cmdline(pid: Int): String = try {
        File("/proc/$pid/cmdline").readText().replace('\u0000', ' ').trim()
    } catch (_: Exception) {
        ""
    }
}

/** Remembers child pids on disk so a crashed/killed app can reap orphans on the next start. */
class ProcessRegistry(context: Context) {
    private val file = File(context.filesDir, "run/pids.txt").also { it.parentFile?.mkdirs() }

    @Synchronized
    fun register(pid: Int, marker: String) {
        if (pid <= 0) return
        file.appendText("$pid|$marker\n")
    }

    @Synchronized
    fun unregister(pid: Int) {
        if (!file.isFile) return
        val kept = file.readLines().filter { it.substringBefore('|') != pid.toString() }
        file.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
    }

    /** Kill leftovers from a previous app process (only if the command line still matches). */
    @Synchronized
    fun reapStale() {
        if (!file.isFile) return
        for (line in file.readLines()) {
            val pid = line.substringBefore('|').toIntOrNull() ?: continue
            val marker = line.substringAfter('|', "")
            if (ProcessUtil.isAlive(pid) && marker.isNotEmpty() && ProcessUtil.cmdline(pid).contains(marker)) {
                ProcessUtil.killTree(pid, 1000)
            }
        }
        file.writeText("")
    }
}

object ElfInfo {
    data class Info(
        val isElf: Boolean,
        val arch: String,
        val is64: Boolean,
        val interpreter: String?
    ) {
        val isArm64: Boolean get() = isElf && arch == "aarch64"
        fun describe(): String {
            if (!isElf) return "not an ELF executable"
            val kind = when {
                interpreter == null -> "static"
                interpreter.contains("linker") -> "Android dynamic"
                interpreter.contains("ld-linux") || interpreter.contains("ld-musl") -> "Linux dynamic ($interpreter)"
                else -> "dynamic ($interpreter)"
            }
            return "${if (is64) "ELF64" else "ELF32"} $arch, $kind"
        }
    }

    fun read(file: File): Info {
        val none = Info(false, "unknown", false, null)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 64) return none
                val head = ByteArray(64)
                raf.readFully(head)
                if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                    head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
                ) return none
                val is64 = head[4].toInt() == 2
                val order = if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val bb = ByteBuffer.wrap(head).order(order)
                val machine = bb.getShort(18).toInt() and 0xFFFF
                val arch = when (machine) {
                    0xB7 -> "aarch64"
                    0x3E -> "x86_64"
                    0x28 -> "arm"
                    0x03 -> "x86"
                    else -> "machine-$machine"
                }
                var interp: String? = null
                if (is64) {
                    val phoff = bb.getLong(32)
                    val phentsize = bb.getShort(54).toInt() and 0xFFFF
                    val phnum = bb.getShort(56).toInt() and 0xFFFF
                    for (i in 0 until phnum.coerceAtMost(64)) {
                        raf.seek(phoff + i.toLong() * phentsize)
                        val ph = ByteArray(56)
                        if (raf.read(ph) < 56) break
                        val pb = ByteBuffer.wrap(ph).order(order)
                        if (pb.getInt(0) == 3) {
                            val off = pb.getLong(8)
                            val size = pb.getLong(32).toInt().coerceIn(1, 256)
                            raf.seek(off)
                            val s = ByteArray(size)
                            raf.readFully(s)
                            interp = String(s, Charsets.US_ASCII).trimEnd('\u0000')
                            break
                        }
                    }
                }
                Info(true, arch, is64, interp)
            }
        } catch (_: Exception) {
            none
        }
    }
}

object ExecUtil {
    data class Probe(
        val ok: Boolean,
        val output: String,
        val viaLinker: Boolean,
        val error: String?
    )

    private val linker: File? = listOf("/system/bin/linker64", "/system/bin/linker").map { File(it) }.firstOrNull { it.exists() }

    fun command(binary: File, args: List<String>, viaLinker: Boolean): List<String> {
        val l = linker
        return if (viaLinker && l != null) listOf(l.absolutePath, binary.absolutePath) + args
        else listOf(binary.absolutePath) + args
    }

    /** Runs `binary args` and checks that it really executes on this device. Tries direct exec, then the linker. */
    fun probe(binary: File, args: List<String>, timeoutMs: Long = 10_000): Probe {
        val direct = tryRun(binary, args, false, timeoutMs)
        if (direct.ok) return direct
        val permissionDenied = direct.error?.contains("Permission denied", ignoreCase = true) == true ||
            direct.error?.contains("error=13") == true
        if (permissionDenied && linker != null) {
            val viaLinker = tryRun(binary, args, true, timeoutMs)
            if (viaLinker.ok) return viaLinker
            return direct.copy(error = (direct.error ?: "") + " (linker fallback also failed: ${viaLinker.error ?: viaLinker.output.take(120)})")
        }
        return direct
    }

    private fun tryRun(binary: File, args: List<String>, viaLinker: Boolean, timeoutMs: Long): Probe {
        return try {
            val pb = ProcessBuilder(command(binary, args, viaLinker))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                return Probe(false, "", viaLinker, "timed out after ${timeoutMs / 1000}s")
            }
            val text = p.inputStream.bufferedReader().use { it.readText() }.take(2000).trim()
            if (p.exitValue() == 0) Probe(true, text, viaLinker, null)
            else Probe(false, text, viaLinker, "exit code ${p.exitValue()}: ${text.take(200)}")
        } catch (e: Exception) {
            Probe(false, "", viaLinker, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Human readable explanation for a failed exec. */
    fun explain(error: String?, info: ElfInfo.Info): String {
        val e = error.orEmpty()
        return when {
            !info.isElf -> "The file is not a Linux executable (ELF)."
            !info.isArm64 -> "Wrong CPU architecture: binary is ${info.arch}, this app needs aarch64 (ARM64)."
            e.contains("Permission denied", true) || e.contains("error=13") ->
                "Android blocks executing files from app storage for apps targeting API 29+ (W^X). " +
                    "Use a build that bundles the binary as a native library (the GitHub Actions workflow does this), " +
                    "or install a different Android-compatible build."
            e.contains("No such file", true) || e.contains("error=2") ->
                "The binary needs a loader that does not exist on Android (${info.interpreter ?: "unknown"}). " +
                    "It is a glibc/Linux build that is incompatible with Android."
            else -> "Binary failed to run: $e"
        }
    }
}
