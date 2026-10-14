package com.imayx.host.data

import android.content.Context
import com.imayx.host.utils.PathUtil
import org.json.JSONObject
import java.io.File

/**
 * Project configs are JSON files in filesDir/project-configs/ — deliberately OUTSIDE the served
 * project directory so they can never be fetched over HTTP or leak into exported ZIPs.
 */
class ProjectStore(context: Context) {

    val projectsRoot = File(context.filesDir, "projects").also { it.mkdirs() }
    private val configDir = File(context.filesDir, "project-configs").also { it.mkdirs() }

    fun projectDir(name: String): File = File(projectsRoot, name)

    /** Directory that is actually served (project dir + optional sub-directory). */
    fun serveRoot(cfg: ProjectConfig): File {
        val base = projectDir(cfg.name)
        if (cfg.rootDir.isBlank()) return base
        return PathUtil.resolveInside(base, cfg.rootDir) ?: base
    }

    private fun configFile(name: String) = File(configDir, "$name.json")

    fun get(name: String): ProjectConfig? {
        if (!PathUtil.isValidProjectName(name)) return null
        val f = configFile(name)
        if (!f.isFile) {
            return if (projectDir(name).isDirectory) ProjectConfig(name = name) else null
        }
        return try {
            fromJson(JSONObject(f.readText()), name)
        } catch (_: Exception) {
            ProjectConfig(name = name)
        }
    }

    fun list(): List<ProjectConfig> {
        val names = projectsRoot.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return names.filter { PathUtil.isValidProjectName(it) }
            .mapNotNull { get(it) }
            .sortedBy { it.name.lowercase() }
    }

    fun exists(name: String): Boolean = projectDir(name).exists()

    fun hasConfig(name: String): Boolean = configFile(name).isFile

    fun save(cfg: ProjectConfig) {
        require(PathUtil.isValidProjectName(cfg.name)) { "Invalid project name" }
        configFile(cfg.name).writeText(toJson(cfg).toString())
    }

    fun deleteConfig(name: String) {
        configFile(name).delete()
    }

    fun renameConfig(from: String, to: String) {
        val src = configFile(from)
        if (src.isFile) src.renameTo(configFile(to))
    }

    private fun toJson(c: ProjectConfig): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("runtime", c.runtime.name)
        put("port", c.port)
        put("rootDir", c.rootDir)
        put("spa", c.spa)
        put("domain", c.domain)
        put("cloudflareEnabled", c.cloudflareEnabled)
        put("autoStart", c.autoStart)
        put("autoRestart", c.autoRestart)
        put("entryFile", c.entryFile)
        put("nodeArgs", c.nodeArgs)
        put("createdAt", c.createdAt)
    }

    private fun fromJson(o: JSONObject, name: String): ProjectConfig {
        val runtime = try {
            RuntimeType.valueOf(o.optString("runtime", "STATIC"))
        } catch (_: Exception) {
            RuntimeType.STATIC
        }
        return ProjectConfig(
            name = name,
            runtime = runtime,
            port = o.optInt("port", 3000).coerceIn(1024, 65535),
            rootDir = o.optString("rootDir", ""),
            spa = o.optBoolean("spa", false),
            domain = o.optString("domain", ""),
            cloudflareEnabled = o.optBoolean("cloudflareEnabled", true),
            autoStart = o.optBoolean("autoStart", false),
            autoRestart = o.optBoolean("autoRestart", false),
            entryFile = o.optString("entryFile", "server.js"),
            nodeArgs = o.optString("nodeArgs", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
