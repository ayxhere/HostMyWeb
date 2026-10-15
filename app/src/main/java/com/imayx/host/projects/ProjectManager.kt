package com.imayx.host.projects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectInfo
import com.imayx.host.data.ProjectStore
import com.imayx.host.data.RuntimeType
import com.imayx.host.security.SecretStore
import com.imayx.host.utils.PathUtil
import java.io.File
import java.io.OutputStream

class ProjectException(message: String) : Exception(message)

class ProjectManager(
    private val context: Context,
    val store: ProjectStore,
    private val settings: AppSettings,
    private val secrets: SecretStore
) {

    fun list(): List<ProjectConfig> = store.list()

    fun get(name: String): ProjectConfig? = store.get(name)

    fun info(cfg: ProjectConfig): ProjectInfo {
        val dir = store.projectDir(cfg.name)
        return ProjectInfo(cfg, PathUtil.folderSize(dir), PathUtil.fileCount(dir), PathUtil.lastModified(dir))
    }

    fun listInfo(): List<ProjectInfo> = list().map { info(it) }

    /** First port at or above the default that no project uses (not the primary port, not the embedded 3101/3102). */
    fun nextFreePort(): Int {
        val used = store.list().map { it.port }.toSet() + setOf(3101, 3102, settings.primaryPort)
        var p = settings.defaultPort
        while (p in used) p++
        return p
    }

    // ---- Server (Node.js) project support -------------------------------------------------------

    /** Entry-point candidates, in preference order. */
    private val serverEntryCandidates =
        listOf("server.js", "src/server.js", "index.js", "src/index.js", "app.js", "src/app.js", "main.js")

    /** File names that look like bundled secrets — never imported into a server project. */
    private val secretFileNames = setOf(".env", "mail-key.json")

    private fun isSecretFileName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base in secretFileNames || base.endsWith(".key.json") || base.endsWith(".pem") ||
            (base.startsWith(".env.") && !base.endsWith(".example"))
    }

    private fun isSafeRelative(p: String): Boolean =
        p.isNotBlank() && !p.startsWith("/") && !p.split('/').contains("..") && '\u0000' !in p

    /**
     * Finds the Node.js entry file inside an extracted project directory.
     * Order: package.json `scripts.start` ("node <file>") → package.json `main` → well-known candidates.
     * Returns the path relative to [dir], or null when nothing looks runnable.
     */
    fun detectServerEntry(dir: File): String? {
        val pkg = File(dir, "package.json")
        if (pkg.isFile) {
            try {
                val json = org.json.JSONObject(pkg.readText())
                val start = json.optJSONObject("scripts")?.optString("start").orEmpty()
                val m = Regex("""node\s+([^\s'";]+)""").find(start)
                val fromStart = m?.groupValues?.getOrNull(1)
                if (fromStart != null && isSafeRelative(fromStart) && File(dir, fromStart).isFile) return fromStart
                val main = json.optString("main").trim()
                if (main.isNotEmpty() && isSafeRelative(main) && File(dir, main).isFile) return main
            } catch (_: Exception) {
            }
        }
        return serverEntryCandidates.firstOrNull { File(dir, it).isFile }
    }

    /** What the import dialog shows when a ZIP looks like a Node.js server. */
    data class ServerDetection(
        /** Entry file guess from the ZIP listing (confirmed against real files after extraction). */
        val entryGuess: String,
        val hasNodeModules: Boolean,
        val skippedSecrets: List<String>
    )

    /**
     * Quick pre-extraction analysis: does this ZIP look like a Node.js server?
     * Names are relative to the would-be project root (strip prefix applied).
     */
    fun analyzeZipForServer(info: ZipInfo): ServerDetection? {
        val rel = info.entryNames.map { n ->
            if (info.stripPrefix.isNotEmpty() && n.startsWith(info.stripPrefix)) n.removePrefix(info.stripPrefix) else n
        }.filter { it.isNotEmpty() }
        if (rel.isEmpty()) return null
        val set = rel.toSet()
        val looksServer = "package.json" in set || serverEntryCandidates.any { it in set }
        if (!looksServer) return null
        val entryGuess = serverEntryCandidates.firstOrNull { it in set } ?: "server.js"
        val hasModules = set.any { it == "node_modules" || it.startsWith("node_modules/") }
        val skipped = rel.filter { '/' !in it && isSecretFileName(it) }.distinct().sorted()
        return ServerDetection(entryGuess, hasModules, skipped)
    }

    /** Result of [importStaged]: the saved config plus human-readable import notes. */
    data class ImportResult(
        val config: ProjectConfig,
        /** Detected server entry file, or null for static projects. */
        val serverEntry: String?,
        val warnings: List<String>
    )

    private fun requireValidName(name: String) {
        if (!PathUtil.isValidProjectName(name)) {
            throw ProjectException("Invalid name. Use letters, digits, '.', '_' or '-' (max 48 characters, must start with a letter or digit).")
        }
    }

    fun create(name: String, runtime: RuntimeType = RuntimeType.STATIC): ProjectConfig {
        requireValidName(name)
        if (store.exists(name)) throw ProjectException("A project named \"$name\" already exists")
        val dir = store.projectDir(name)
        if (!dir.mkdirs()) throw ProjectException("Could not create project directory")
        val cfg = ProjectConfig(name = name, runtime = runtime, port = nextFreePort())
        store.save(cfg)
        if (runtime == RuntimeType.STATIC) {
            File(dir, "index.html").writeText(starterHtml(name))
        } else {
            File(dir, "server.js").writeText(starterNode())
        }
        if (settings.activeProject.isBlank()) settings.activeProject = name
        LogBus.i(LogCategory.SYSTEM, "Project created: $name (${runtime.name})")
        return cfg
    }

    fun save(cfg: ProjectConfig) {
        store.save(cfg)
    }

    fun rename(old: String, new: String) {
        requireValidName(new)
        if (old == new) return
        if (store.exists(new)) throw ProjectException("A project named \"$new\" already exists")
        val src = store.projectDir(old)
        if (!src.isDirectory) throw ProjectException("Project not found")
        if (!src.renameTo(store.projectDir(new))) throw ProjectException("Rename failed")
        val cfg = store.get(old) ?: ProjectConfig(name = old)
        store.deleteConfig(old)
        store.save(cfg.copy(name = new))
        secrets.moveEnv(old, new)
        if (settings.activeProject == old) settings.activeProject = new
        LogBus.i(LogCategory.SYSTEM, "Project renamed: $old → $new")
    }

    fun delete(name: String) {
        val dir = store.projectDir(name)
        if (!dir.exists()) return
        val canonicalRoot = store.projectsRoot.canonicalFile
        if (!PathUtil.isInside(canonicalRoot, dir.canonicalFile) || dir.canonicalFile == canonicalRoot) {
            throw ProjectException("Refusing to delete outside the projects directory")
        }
        dir.deleteRecursively()
        store.deleteConfig(name)
        secrets.removeEnv(name)
        if (settings.activeProject == name) {
            settings.activeProject = store.list().firstOrNull()?.name ?: ""
        }
        LogBus.i(LogCategory.SYSTEM, "Project deleted: $name")
    }

    fun duplicate(name: String, newName: String): ProjectConfig {
        requireValidName(newName)
        if (store.exists(newName)) throw ProjectException("A project named \"$newName\" already exists")
        val src = store.projectDir(name)
        if (!src.isDirectory) throw ProjectException("Project not found")
        val dst = store.projectDir(newName)
        if (!src.copyRecursively(dst, overwrite = false)) {
            dst.deleteRecursively()
            throw ProjectException("Copy failed")
        }
        val cfg = (store.get(name) ?: ProjectConfig(name = name)).copy(
            name = newName,
            autoStart = false,
            createdAt = System.currentTimeMillis()
        )
        store.save(cfg)
        secrets.setEnv(newName, secrets.getEnv(name))
        LogBus.i(LogCategory.SYSTEM, "Project duplicated: $name → $newName")
        return cfg
    }

    fun setActive(name: String) {
        if (store.exists(name)) settings.activeProject = name
    }

    // ---- ZIP import / export -------------------------------------------------------------------

    fun displayName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx) ?: ""
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "website.zip"
    }

    fun suggestName(uri: Uri): String =
        PathUtil.sanitizeProjectName(displayName(uri).removeSuffix(".zip").removeSuffix(".ZIP"))

    /** Copy the picked document into our cache (so we can read the ZIP central directory) and inspect it. */
    fun stageZip(uri: Uri): Pair<File, ZipInfo> {
        val dir = File(context.cacheDir, "import").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "upload.zip")
        val input = context.contentResolver.openInputStream(uri) ?: throw ProjectException("Cannot open the selected file")
        input.use { ZipTools.copyToFile(it, tmp) }
        return tmp to ZipTools.inspect(tmp)
    }

    fun importStaged(zip: File, info: ZipInfo, name: String, onProgress: ((Int, Int) -> Unit)? = null): ImportResult {
        requireValidName(name)
        if (!info.isSafe) {
            throw ProjectException(info.error ?: "ZIP contains unsafe paths and was rejected")
        }
        if (store.exists(name)) throw ProjectException("A project named \"$name\" already exists")
        val dir = store.projectDir(name)
        try {
            dir.mkdirs()
            val n = ZipTools.extract(zip, dir, info.stripPrefix, onProgress)
            val warnings = ArrayList<String>()

            // Secret quarantine: real keys must live in the app's encrypted env store,
            // never inside an imported project (which can be exported/shared as a ZIP).
            val quarantined = ArrayList<String>()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && isSecretFileName(f.name)) {
                    quarantined.add(f.name)
                    f.delete()
                }
            }
            if (quarantined.isNotEmpty()) {
                val msg = "Skipped secret file(s): ${quarantined.joinToString(", ")} — " +
                    "put keys in Project → Settings → Environment secrets instead."
                warnings.add(msg)
                LogBus.w(LogCategory.SYSTEM, "Import $name: $msg")
            }

            // Server detection: a Node.js project becomes a SERVER project (built-in
            // Node.js, one-tap start) instead of a static site.
            val entry = detectServerEntry(dir)
            val cfg = if (entry != null) {
                if (File(dir, "package.json").isFile && !File(dir, "node_modules").isDirectory) {
                    warnings.add(
                        "node_modules not found in this ZIP — the server will fail to start until " +
                            "its npm dependencies are included. Re-export the ZIP with node_modules " +
                            "(production install) and re-import."
                    )
                }
                LogBus.i(LogCategory.SYSTEM, "Imported ZIP into server project $name ($n files, entry=$entry)")
                ProjectConfig(name = name, runtime = RuntimeType.SERVER, port = nextFreePort(), entryFile = entry)
            } else {
                LogBus.i(LogCategory.SYSTEM, "Imported ZIP into project $name ($n files)")
                ProjectConfig(name = name, port = nextFreePort())
            }
            store.save(cfg)
            if (settings.activeProject.isBlank()) settings.activeProject = name
            return ImportResult(cfg, entry, warnings)
        } catch (e: Exception) {
            dir.deleteRecursively()
            store.deleteConfig(name)
            LogBus.e(LogCategory.SYSTEM, "ZIP import failed: ${e.message}")
            throw if (e is ProjectException) e else ProjectException(e.message ?: "Import failed")
        } finally {
            zip.delete()
        }
    }

    fun exportZip(name: String, out: OutputStream): Int {
        val dir = store.projectDir(name)
        if (!dir.isDirectory) throw ProjectException("Project not found")
        return ZipTools.zipDirectory(dir, out)
    }

    private fun starterHtml(name: String) = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$name</title>
<style>
  html,body{height:100%;margin:0;background:#000;color:#eee;font-family:system-ui,sans-serif}
  main{min-height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px}
  h1{margin:0;font-weight:600}
  p{margin:0;color:#9a9aa6}
</style>
</head>
<body>
<main>
  <h1>$name</h1>
  <p>Hosted with IMAYX Host. Edit this page in the Editor tab.</p>
</main>
</body>
</html>
"""

    private fun starterNode() = """const http = require('http');
const port = parseInt(process.env.PORT || '3000', 10);
http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Hello from IMAYX Host (Node)\n');
}).listen(port, process.env.HOST || '127.0.0.1', () => console.log('listening on ' + port));
"""
}
