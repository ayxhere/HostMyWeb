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
        val cfg = ProjectConfig(name = name, runtime = runtime, port = settings.defaultPort)
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

    fun importStaged(zip: File, info: ZipInfo, name: String, onProgress: ((Int, Int) -> Unit)? = null): ProjectConfig {
        requireValidName(name)
        if (!info.isSafe) {
            throw ProjectException(info.error ?: "ZIP contains unsafe paths and was rejected")
        }
        if (store.exists(name)) throw ProjectException("A project named \"$name\" already exists")
        val dir = store.projectDir(name)
        try {
            dir.mkdirs()
            val n = ZipTools.extract(zip, dir, info.stripPrefix, onProgress)
            val cfg = ProjectConfig(name = name, port = settings.defaultPort)
            store.save(cfg)
            if (settings.activeProject.isBlank()) settings.activeProject = name
            LogBus.i(LogCategory.SYSTEM, "Imported ZIP into project $name ($n files)")
            return cfg
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
