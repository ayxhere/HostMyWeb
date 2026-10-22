package com.imayx.host.runtime

import android.content.Context
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.projects.ZipTools
import com.imayx.host.utils.Downloader
import com.imayx.host.utils.TarTools
import java.io.File
import java.io.InputStream

data class InstallResult(val ok: Boolean, val message: String)

/**
 * Manages the Node.js runtime. Only Android-compatible ARM64 builds are accepted: the file must be an
 * aarch64 ELF and must actually execute (`node --version`). A stock Linux/glibc Node binary is NOT assumed
 * to work and is rejected with a clear message.
 */
class NodeInstaller(private val context: Context) {

    private val dir = File(context.filesDir, "runtime/node")
    private val installed = File(dir, "bin/node")
    private val bundled: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    @Volatile
    private var cachedProbe: ExecUtil.Probe? = null

    // Priority: a user-installed executable wins. The bundled libnode.so is a
    // shared library (loaded in-process by the Server runtime via JNI) — it can
    // never be executed as a child process, so it must not shadow a real node
    // binary for NODE projects / one-off scripts.
    fun binary(): File? = when {
        installed.isFile -> installed
        bundled.isFile -> bundled
        else -> null
    }

    fun isInstalled(): Boolean = binary() != null

    fun source(): String? = when {
        installed.isFile -> "downloaded"
        bundled.isFile -> "bundled"
        else -> null
    }

    fun elfInfo(): ElfInfo.Info? = binary()?.let { ElfInfo.read(it) }

    fun probe(force: Boolean = false): ExecUtil.Probe? {
        val b = binary() ?: return null
        if (!force) cachedProbe?.let { return it }
        val r = ExecUtil.probe(b, listOf("--version"))
        cachedProbe = r
        return r
    }

    fun version(): String? {
        val p = probe() ?: return null
        return if (p.ok) p.output.lineSequence().firstOrNull()?.trim() else null
    }

    fun uninstall() {
        dir.deleteRecursively()
        cachedProbe = null
        LogBus.i(LogCategory.NODE, "Node runtime removed")
    }

    fun installFromUrl(url: String, onProgress: (Int) -> Unit): InstallResult {
        if (url.isBlank()) {
            return InstallResult(false, "No Node runtime URL configured. Set an Android-compatible ARM64 Node build URL (https) in Settings → Node Runtime.")
        }
        val tmp = File(context.cacheDir, "node-download.bin")
        return try {
            LogBus.i(LogCategory.NODE, "Downloading Node runtime from ${url.substringBefore('?')}")
            Downloader.download(url, tmp, onProgress = onProgress)
            installFromFile(tmp)
        } catch (e: Exception) {
            InstallResult(false, "Download failed: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    fun installFromStream(input: InputStream): InstallResult {
        val tmp = File(context.cacheDir, "node-import.bin")
        return try {
            ZipTools.copyToFile(input, tmp, 700L * 1024 * 1024)
            installFromFile(tmp)
        } catch (e: Exception) {
            InstallResult(false, "Import failed: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    private fun installFromFile(file: File): InstallResult {
        val magic = Downloader.magic(file)
        dir.deleteRecursively()
        File(dir, "bin").mkdirs()
        try {
            when {
                magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() -> {
                    file.copyTo(installed, overwrite = true)
                }
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() -> {
                    val info = ZipTools.inspect(file)
                    if (info.error != null || info.unsafeEntries.isNotEmpty()) {
                        return InstallResult(false, info.error ?: "Archive contains unsafe paths")
                    }
                    ZipTools.extract(file, dir, "")
                    locateNode()
                }
                magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> {
                    file.inputStream().use { TarTools.extractTarGz(it, dir) }
                    locateNode()
                }
                else -> return InstallResult(false, "Unsupported file. Provide an ARM64 node executable, .zip or .tar.gz")
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            return InstallResult(false, "Extraction failed: ${e.message}")
        }
        if (!installed.isFile) {
            dir.deleteRecursively()
            return InstallResult(false, "No 'node' executable found in the archive")
        }
        installed.setExecutable(true, false)
        val info = ElfInfo.read(installed)
        if (!info.isArm64) {
            dir.deleteRecursively()
            return InstallResult(false, ExecUtil.explain(null, info))
        }
        val probe = probe(force = true)
        if (probe == null || !probe.ok) {
            val msg = ExecUtil.explain(probe?.error, info)
            LogBus.e(LogCategory.NODE, "Node runtime does not run on this device: $msg")
            return InstallResult(false, "Installed but cannot run: $msg")
        }
        LogBus.i(LogCategory.NODE, "Node runtime installed: ${probe.output.lineSequence().firstOrNull()}")
        return InstallResult(true, "Node ${probe.output.lineSequence().firstOrNull()?.trim()} installed (${info.describe()})")
    }

    /** After extracting an archive, move the first ELF file called "node" to bin/node. */
    private fun locateNode() {
        if (installed.isFile && ElfInfo.read(installed).isElf) return
        var best: File? = null
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (c in d.listFiles().orEmpty()) {
                if (c.isDirectory) stack.add(c)
                else if (c.name == "node" && c != installed && ElfInfo.read(c).isElf) {
                    if (best == null || c.path.contains("/bin/")) best = c
                }
            }
        }
        val found = best ?: return
        installed.parentFile?.mkdirs()
        found.copyTo(installed, overwrite = true)
    }
}
