package com.imayx.host.cloudflare

import android.content.Context
import android.util.Base64
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.runtime.ElfInfo
import com.imayx.host.runtime.ExecUtil
import com.imayx.host.runtime.InstallResult
import com.imayx.host.projects.ZipTools
import com.imayx.host.utils.Downloader
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** What we can learn from a tunnel token WITHOUT exposing its secret part ("s"). */
data class TunnelTokenInfo(val accountId: String, val tunnelId: String)

object TunnelToken {
    /**
     * Pastes often contain extra words ("cloudflared service install eyJ…"), line breaks or URL-safe base64.
     * Go's decoder inside cloudflared is strict (standard alphabet, padded, no whitespace), so we re-encode the
     * decoded JSON into canonical form. Returns null when [raw] is not a tunnel token.
     */
    fun canonicalize(raw: String): String? {
        val candidate = raw.trim().split(Regex("\\s+")).let { parts ->
            parts.firstOrNull { it.startsWith("eyJ") && it.length >= 40 } ?: parts.joinToString("")
        }
        if (candidate.isEmpty()) return null
        val normalized = candidate.replace('-', '+').replace('_', '/')
        val decoded = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        return try {
            val o = JSONObject(String(decoded, Charsets.UTF_8))
            if (o.optString("a").isBlank() || o.optString("t").isBlank() || !o.has("s")) null
            else Base64.encodeToString(decoded, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A Cloudflare Tunnel token is base64(JSON {"a": accountId, "t": tunnelId, "s": secret}).
     * We only ever return a and t. This is NOT a Cloudflare dashboard/API credential.
     */
    fun parse(token: String): TunnelTokenInfo? {
        val t = token.trim()
        if (t.length < 40) return null
        val normalized = t.replace('-', '+').replace('_', '/')
        val decoded = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        return try {
            val o = JSONObject(String(decoded, Charsets.UTF_8))
            val a = o.optString("a", "")
            val id = o.optString("t", "")
            if (a.isBlank() || id.isBlank() || !o.has("s")) null else TunnelTokenInfo(a, id)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Installs and locates the official ARM64 `cloudflared`. The binary is looked up in this order:
 *  1. libcloudflared.so in the app's native library dir (bundled by CI; the only location Android 10+
 *     reliably allows exec() from for apps targeting API 29+),
 *  2. filesDir/bin/cloudflared (downloaded by the app from the official Cloudflare GitHub release).
 */
class CloudflareManager(private val context: Context, private val settings: AppSettings) {

    private val binDir = File(context.filesDir, "bin")
    private val downloaded = File(binDir, "cloudflared")
    private val bundled: File get() = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    @Volatile
    private var cachedProbe: ExecUtil.Probe? = null

    fun binary(): File? = when {
        bundled.isFile -> bundled
        downloaded.isFile -> downloaded
        else -> null
    }

    fun isInstalled(): Boolean = binary() != null

    fun source(): String? = when {
        bundled.isFile -> "bundled with app"
        downloaded.isFile -> "downloaded by app"
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
        if (!p.ok) return null
        return p.output.lineSequence().firstOrNull { it.contains("cloudflared") }?.trim()
            ?: p.output.lineSequence().firstOrNull()?.trim()
    }

    /** Command line used to launch cloudflared (token is passed as an argument, never logged). */
    fun command(args: List<String>): List<String>? {
        val b = binary() ?: return null
        val viaLinker = probe()?.viaLinker == true
        return ExecUtil.command(b, args, viaLinker)
    }

    @Volatile
    private var helpText: String? = null

    /** Does `cloudflared tunnel --help` list [flag]? Cached; false when it cannot be run. */
    fun supportsFlag(flag: String): Boolean {
        var text = helpText
        if (text == null) {
            text = ""
            try {
                val cmd = command(listOf("tunnel", "--help"))
                if (cmd != null) {
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    text = p.inputStream.bufferedReader().use { it.readText() }
                    p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                    p.destroyForcibly()
                }
            } catch (_: Exception) {
            }
            helpText = text
        }
        return text.contains(flag)
    }

    fun uninstall() {
        downloaded.delete()
        cachedProbe = null
    }

    fun install(onProgress: (Int) -> Unit): InstallResult {
        val tmp = File(context.cacheDir, "cloudflared-download.bin")
        try {
            binDir.mkdirs()
            val custom = settings.cloudflaredUrl
            var expectedSha: String? = null
            val url: String
            if (custom.isNotBlank()) {
                url = custom
                LogBus.i(LogCategory.CLOUDFLARE, "Downloading cloudflared from custom URL")
            } else {
                LogBus.i(LogCategory.CLOUDFLARE, "Querying latest official cloudflared release")
                val json = JSONObject(
                    Downloader.getText(
                        "https://api.github.com/repos/cloudflare/cloudflared/releases/latest",
                        mapOf("Accept" to "application/vnd.github+json")
                    )
                )
                val assets = json.optJSONArray("assets")
                    ?: return InstallResult(false, "Release has no assets")
                var found: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "cloudflared-linux-arm64") {
                        found = a.optString("browser_download_url")
                        val digest = a.optString("digest", "")
                        if (digest.startsWith("sha256:")) expectedSha = digest.removePrefix("sha256:")
                        break
                    }
                }
                url = found ?: return InstallResult(false, "No cloudflared-linux-arm64 asset in the latest release")
                LogBus.i(LogCategory.CLOUDFLARE, "Found ${json.optString("tag_name")} linux-arm64")
            }
            Downloader.download(url, tmp, 200L * 1024 * 1024, onProgress)
            return finishInstall(tmp, expectedSha)
        } catch (e: Exception) {
            val msg = "Download failed: ${e.message ?: e.javaClass.simpleName}. Check your network connection."
            LogBus.e(LogCategory.CLOUDFLARE, msg)
            return InstallResult(false, msg)
        } finally {
            tmp.delete()
        }
    }

    fun installFromStream(input: InputStream): InstallResult {
        val tmp = File(context.cacheDir, "cloudflared-import.bin")
        return try {
            binDir.mkdirs()
            ZipTools.copyToFile(input, tmp, 200L * 1024 * 1024)
            finishInstall(tmp, null)
        } catch (e: Exception) {
            InstallResult(false, "Import failed: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    private fun finishInstall(tmp: File, expectedSha: String?): InstallResult {
        val info = ElfInfo.read(tmp)
        if (!info.isElf) return InstallResult(false, "Downloaded file is not a Linux executable.")
        if (!info.isArm64) {
            return InstallResult(false, "cloudflared binary incompatible: architecture is ${info.arch}, ARM64 (aarch64) is required.")
        }
        if (expectedSha != null) {
            val actual = Downloader.sha256(tmp)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                return InstallResult(false, "Checksum mismatch — the download was rejected.")
            }
            LogBus.i(LogCategory.CLOUDFLARE, "SHA-256 verified")
        }
        tmp.copyTo(downloaded, overwrite = true)
        downloaded.setExecutable(true, false)
        cachedProbe = null
        val probe = probe(force = true)
        return if (probe != null && probe.ok) {
            val v = version() ?: "cloudflared"
            LogBus.i(LogCategory.CLOUDFLARE, "Installed and verified: $v (${info.describe()})")
            InstallResult(true, "$v installed (${info.describe()})")
        } else {
            val why = ExecUtil.explain(probe?.error, info)
            LogBus.e(LogCategory.CLOUDFLARE, "cloudflared binary incompatible: $why")
            InstallResult(false, "cloudflared binary incompatible: $why")
        }
    }
}
