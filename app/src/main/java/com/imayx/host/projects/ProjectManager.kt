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
        // ONE traversal for size + count + mtime (was three full walks — on a
        // 7k-file node_modules tree that tripled the cost of every list refresh).
        val s = PathUtil.folderStats(dir)
        return ProjectInfo(cfg, s.sizeBytes, s.fileCount, s.lastModified)
    }

    fun listInfo(): List<ProjectInfo> = list().map { info(it) }

    /** First port at or above the default that no project uses (not the primary port, not the embedded 3101/3102). */
    fun nextFreePort(): Int {
        val used = store.list().flatMap { listOf(it.port) + it.servers.map { sv -> sv.port } }.toSet() +
            setOf(3101, 3102, settings.primaryPort)
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
        val skippedSecrets: List<String>,
        /** Every server folder found in the ZIP listing (>1 = multi-server ZIP). */
        val servers: List<ServerScanner.Found> = emptyList(),
        /** [servers] turned into named/ported defs for the import dialog (editable ports). */
        val defs: List<com.imayx.host.data.ServerDef> = emptyList()
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
        val looksServer = "package.json" in set || serverEntryCandidates.any { it in set } ||
            ServerScanner.scanNames(rel).isNotEmpty()
        if (!looksServer) return null
        val entryGuess = serverEntryCandidates.firstOrNull { it in set } ?: ""
        val hasModules = set.any { it == "node_modules" || it.startsWith("node_modules/") }
        val skipped = rel.filter { '/' !in it && isSecretFileName(it) }.distinct().sorted()
        return ServerDetection(entryGuess, hasModules, skipped, ServerScanner.scanNames(rel))
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
        // create() writes a real server.js starter for NODE/SERVER projects, so the
        // entry is a fact there; STATIC projects have no entry at all.
        val cfg = ProjectConfig(
            name = name, runtime = runtime, port = nextFreePort(),
            entryFile = if (runtime == RuntimeType.STATIC) "" else "server.js"
        )
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

    fun importStaged(
        zip: File, info: ZipInfo, name: String,
        defsOverride: List<com.imayx.host.data.ServerDef>? = null,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ImportResult {
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
            // Root AND server sub-folders (multi-server ZIPs keep a .env per server); node_modules is skipped.
            fun sweep(d: File, depth: Int) {
                d.listFiles()?.forEach { f ->
                    if (f.isFile && isSecretFileName(f.name)) {
                        quarantined.add(f.relativeTo(dir).path)
                        f.delete()
                    } else if (f.isDirectory && depth < 3 && f.name != "node_modules" && f.name != ".git") {
                        sweep(f, depth + 1)
                    }
                }
            }
            sweep(dir, 0)
            if (quarantined.isNotEmpty()) {
                val msg = "Skipped secret file(s): ${quarantined.joinToString(", ")} — " +
                    "put keys in Project → Settings → Environment secrets instead."
                warnings.add(msg)
                LogBus.w(LogCategory.SYSTEM, "Import $name: $msg")
            }

            // Server detection: a Node.js project becomes a SERVER project (built-in
            // Node.js, one-tap start) instead of a static site.
            val found = ServerScanner.scan(dir)
            val entry = found.firstOrNull { it.dir.isEmpty() }?.entry ?: found.firstOrNull()?.let {
                if (it.dir.isEmpty()) it.entry else "${it.dir}/${it.entry}"
            } ?: detectServerEntry(dir)
            val cfg = if (found.isNotEmpty()) {
                val scanned = buildDefs(name, found)
                // The import dialog's confirmed list wins for ports/enabled when it covers
                // exactly the folders the real-file scan found; entry/name stay authoritative
                // from the scan (the ZIP-listing preview cannot read package.json contents).
                val defs = if (defsOverride != null &&
                    defsOverride.map { it.dir }.toSet() == found.map { it.dir }.toSet()
                ) {
                    scanned.map { s ->
                        val o = defsOverride.first { it.dir == s.dir }
                        s.copy(port = o.port, enabled = o.enabled)
                    }
                } else scanned
                for (d in defs) {
                    val sd = if (d.dir.isEmpty()) dir else File(dir, d.dir)
                    if (File(sd, "package.json").isFile && !ServerScanner.hasNodeModules(dir, sd)) {
                        warnings.add("${d.name}: node_modules not found — this server will fail to start until its dependencies are included.")
                    }
                }
                LogBus.i(LogCategory.SYSTEM, "Imported ZIP into server project $name ($n files, ${defs.size} server(s): " +
                    defs.joinToString { "${it.name}:${it.port}" } + ")")
                val prim = defs.firstOrNull { it.primary } ?: defs.first()
                ProjectConfig(
                    name = name, runtime = RuntimeType.SERVER, port = prim.port,
                    entryFile = if (prim.dir.isEmpty()) prim.entry else "${prim.dir}/${prim.entry}",
                    servers = defs
                )
            } else if (entry != null) {
                if (File(dir, "package.json").isFile && !File(dir, "node_modules").isDirectory) {
                    warnings.add(
                        "node_modules not found in this ZIP — the server will fail to start until " +
                            "its npm dependencies are included. Re-export the ZIP with node_modules " +
                            "(production install) and re-import."
                    )
                }
                LogBus.i(LogCategory.SYSTEM, "Imported ZIP into server project $name ($n files, entry=$entry)")
                // ONE port for both the def and the project: the server binds the
                // def's port and the tunnel targets the project's port — they must
                // never disagree (was: def=primaryPort, project=nextFreePort()).
                val p = nextFreePort()
                val one = com.imayx.host.data.ServerDef(
                    name = PathUtil.sanitizeProjectName(name).lowercase().take(32), dir = "", entry = entry,
                    port = p, subdomain = "", enabled = true, primary = true
                )
                ProjectConfig(name = name, runtime = RuntimeType.SERVER, port = p, entryFile = entry, servers = listOf(one))
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

    /** Assigns names, subdomains and free ports to freshly scanned servers.
     *  [primaryPort] is the port the bundle's main server gets: the global primary
     *  port for a brand-new import, or the PROJECT'S OWN saved port when filling /
     *  re-detecting an existing project (its port must never be renumbered). */
    private fun buildDefs(
        project: String,
        found: List<ServerScanner.Found>,
        primaryPort: Int = settings.primaryPort
    ): List<com.imayx.host.data.ServerDef> {
        val used = store.list().filter { it.name != project }
            .flatMap { listOf(it.port) + it.servers.map { sv -> sv.port } }.toSet()
        return ServerScanner.toDefs(found, project, used, primaryPort, maxOf(settings.defaultPort, 3200))
    }

    /**
     * Names, ports and subdomains for [found] WITHOUT saving. Servers that already exist
     * (matched by folder) keep their saved name/port/subdomain/enabled, so re-detecting
     * never renumbers a working setup; new folders get fresh free ports.
     */
    fun previewDefs(name: String, found: List<ServerScanner.Found>): List<com.imayx.host.data.ServerDef> {
        val existing = store.get(name)
        // An existing project keeps its own port for the main server; only a
        // brand-new import falls back to the global primary port.
        val fresh = buildDefs(name, found, primaryPort = existing?.port ?: settings.primaryPort)
        val cfg = existing ?: return fresh
        val merged = fresh.map { f ->
            val old = cfg.servers.firstOrNull { it.dir == f.dir }
            if (old != null) f.copy(
                name = old.name, port = old.port, subdomain = old.subdomain,
                enabled = old.enabled, primary = old.primary
            ) else f
        }
        // buildDefs cannot see this project's SAVED ports, so a kept old port can collide
        // with a port freshly assigned to a new folder. Resolve duplicates in list order:
        // the earlier server keeps its port, the later one moves to the next free port.
        val taken = store.list().filter { it.name != name }
            .flatMap { listOf(it.port) + it.servers.map { sv -> sv.port } }.toMutableSet()
        taken += setOf(3101, 3102)
        var next = maxOf(settings.defaultPort, 3200)
        return merged.map { d ->
            if (taken.add(d.port)) d else {
                while (next in taken || next == settings.primaryPort) next++
                taken.add(next)
                d.copy(port = next)
            }
        }
    }

    /** Saves [defs] as the project's server list (the project becomes a SERVER project). */
    fun applyServers(name: String, defs: List<com.imayx.host.data.ServerDef>): ProjectConfig {
        val cfg = store.get(name) ?: throw ProjectException("Project not found")
        if (defs.isEmpty()) throw ProjectException("No Node.js server found in this project")
        val prim = defs.firstOrNull { it.primary } ?: defs.first()
        val out = cfg.copy(
            runtime = RuntimeType.SERVER, servers = defs,
            // Keep the project-level port in sync with the main server's port:
            // the server binds def.port, the tunnel targets project.port.
            port = prim.port,
            entryFile = if (prim.dir.isEmpty()) prim.entry else "${prim.dir}/${prim.entry}"
        )
        store.save(out)
        LogBus.i(LogCategory.SYSTEM, "Servers saved for $name: ${defs.size} server(s)")
        return out
    }

    /**
     * Re-detects the servers of an existing project (after the user edited/added folders).
     * Keeps name/subdomain/port/enabled of servers that still exist (matched by folder).
     */
    fun rescanServers(name: String, override: List<ServerScanner.Found>? = null): ProjectConfig {
        val dir = store.projectDir(name)
        val found = override ?: ServerScanner.scan(dir)
        if (found.isEmpty()) throw ProjectException("No Node.js server found in this project")
        val out = applyServers(name, previewDefs(name, found))
        LogBus.i(LogCategory.SYSTEM, "Rescanned $name: ${out.servers.size} server(s)")
        return out
    }

    /**
     * Fills a project's REAL entry from its files on save, so the entry shown and run
     * is the detected path (e.g. src/server.js), never a "server.js" placeholder:
     *  - A SERVER project saved with an empty server list gets the full detected
     *    server list (and its project port synced to the main server's port).
     *  - A NODE project saved with a blank entry gets the detected entry — a blank
     *    entry must never dead-end the settings dialog; the file either exists in
     *    the project (and is used) or the entry stays blank and start reports it.
     * A user-typed entry is never overwritten.
     */
    fun fillDetectedServers(cfg: ProjectConfig): ProjectConfig {
        val dir = store.projectDir(cfg.name)
        if (!dir.isDirectory) return cfg
        if (cfg.runtime == RuntimeType.SERVER && cfg.servers.isEmpty()) {
            val found = ServerScanner.scan(dir)
            if (found.isNotEmpty()) {
                // The project's OWN saved port becomes the main server's port —
                // auto-detect must never renumber a port the user already set.
                val defs = buildDefs(cfg.name, found, primaryPort = cfg.port)
                val prim = defs.firstOrNull { it.primary } ?: defs.first()
                return cfg.copy(
                    servers = defs,
                    port = prim.port,
                    entryFile = if (prim.dir.isEmpty()) prim.entry else "${prim.dir}/${prim.entry}"
                )
            }
            val entry = detectServerEntry(dir)
            // Nothing detected: clear any stale entry (e.g. an old "server.js" guess) —
            // the settings field must show EMPTY, never a made-up default.
            return if (entry != null) cfg.copy(entryFile = entry) else cfg.copy(entryFile = "")
        }
        if (cfg.runtime == RuntimeType.NODE && cfg.entryFile.isBlank()) {
            val found = ServerScanner.scan(dir)
            val prim = found.firstOrNull { it.dir.isEmpty() } ?: found.firstOrNull()
            val entry = prim?.let { if (it.dir.isEmpty()) it.entry else "${it.dir}/${it.entry}" }
                ?: detectServerEntry(dir)
            return if (entry != null) cfg.copy(entryFile = entry) else cfg
        }
        return cfg
    }

    /** Project names already auto-detected in this app session (see [autoDetectServers]). */
    private val autoDetectDone = HashSet<String>()

    /**
     * List-load auto-detection: the app itself understands what is inside a saved
     * project — no Save, no "Detect" button press needed. For every project that
     * was never configured (no saved server list and no entry file), the real
     * scan runs ONCE per app session and what it finds is PERSISTED: the server
     * list (folder · full entry path incl. src/ · auto port) and the primary
     * entry file, so the project card and the settings dialog show the real
     * servers immediately. A never-configured STATIC project whose files contain
     * servers becomes a SERVER project — exactly what importing the same folder
     * would have saved. Rules:
     *  - A project with a saved server list or a user-set entry is configured
     *    and never overridden (a missing entry is only derived from its own
     *    saved list, without scanning).
     *  - No port is ever renumbered: detected defs are built with the project's
     *    OWN saved port for the main server (same builder as the Save path).
     *  - The PENTAGI pseudo-project is not a file project and is never scanned.
     * Returns the configs that were changed (and saved).
     */
    fun autoDetectServers(): List<ProjectConfig> {
        val changed = ArrayList<ProjectConfig>()
        for (cfg in store.list()) {
            if (!autoDetectDone.add(cfg.name)) continue
            try {
                autoDetectProject(cfg)?.let { changed += it }
            } catch (e: Exception) {
                LogBus.w(LogCategory.SYSTEM, "Auto-detect failed for ${cfg.name}: ${e.message}")
            }
        }
        return changed
    }

    private fun autoDetectProject(cfg: ProjectConfig): ProjectConfig? {
        if (cfg.runtime == RuntimeType.PENTAGI) return null
        if (cfg.servers.isNotEmpty()) {
            // Configured already — only derive a missing entry from the list itself.
            if (cfg.entryFile.isNotBlank()) return null
            val prim = cfg.servers.firstOrNull { it.primary } ?: cfg.servers.first()
            val out = cfg.copy(
                entryFile = if (prim.dir.isEmpty()) prim.entry else "${prim.dir}/${prim.entry}"
            )
            store.save(out)
            return out
        }
        // A user-set entry (legacy single-server project) is a configuration — keep it.
        if (cfg.entryFile.isNotBlank()) return null
        val dir = store.projectDir(cfg.name)
        if (!dir.isDirectory) return null
        val found = ServerScanner.scan(dir)
        if (found.isNotEmpty()) {
            val defs = buildDefs(cfg.name, found, primaryPort = cfg.port)
            val prim = defs.firstOrNull { it.primary } ?: defs.first()
            val out = cfg.copy(
                // STATIC becomes SERVER (import semantics); NODE keeps its runtime
                // (it runs the entry file) but still gets the detected list so the
                // settings dialog can show every server in the project.
                runtime = if (cfg.runtime == RuntimeType.STATIC) RuntimeType.SERVER else cfg.runtime,
                servers = defs,
                port = prim.port,
                entryFile = if (prim.dir.isEmpty()) prim.entry else "${prim.dir}/${prim.entry}"
            )
            store.save(out)
            LogBus.i(
                LogCategory.SYSTEM,
                "Auto-detected ${defs.size} server(s) in ${cfg.name}: " +
                    defs.joinToString { "${it.name} ${if (it.dir.isEmpty()) it.entry else it.dir + "/" + it.entry} :${it.port}" }
            )
            return out
        }
        // No server folders: fill a lone entry for NODE/SERVER projects (same rule
        // as the settings Save path). STATIC projects stay exactly as they are.
        if (cfg.runtime == RuntimeType.NODE || cfg.runtime == RuntimeType.SERVER) {
            val entry = detectServerEntry(dir)
            if (entry != null) {
                val out = cfg.copy(entryFile = entry)
                store.save(out)
                LogBus.i(LogCategory.SYSTEM, "Auto-detected entry for ${cfg.name}: $entry")
                return out
            }
        }
        return null
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
