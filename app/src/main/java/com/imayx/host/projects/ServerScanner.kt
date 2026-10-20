package com.imayx.host.projects

import com.imayx.host.data.ServerDef
import org.json.JSONObject
import java.io.File

/**
 * Finds every Node.js server inside a project folder (or a ZIP listing).
 *
 * Rules:
 *  - A directory is a server when it has a runnable entry (package.json scripts.start "node x" -> main ->
 *    server.js, src/server.js, index.js, ...).
 *  - Once a directory is a server, its sub-folders are NOT scanned (src/ of the same server is not a 2nd server).
 *  - node_modules, .git, dist, build, public, assets, etc. are skipped. Depth limit 3.
 *  - Root server (if any) is the primary/apex server. Otherwise a folder named web/www/main/site/app/frontend
 *    is primary; otherwise the first one.
 *  - Folder name becomes the server name AND the subdomain (api/ -> api.<domain>).
 */
object ServerScanner {

    data class Found(val dir: String, val entry: String)

    private val entryCandidates =
        listOf(
            "server.js", "src/server.js", "index.js", "src/index.js", "app.js", "src/app.js", "main.js", "src/main.js",
            "server.mjs", "index.mjs", "app.mjs", "src/server.mjs", "src/index.mjs", "api.js", "bin/www"
        )
    private val skipDirs = setOf(
        "node_modules", ".git", ".github", "dist", "build", "public", "assets", "static", "uploads",
        "data", "tests", "test", "docs", "scripts", "db", "migrations", "coverage", ".next", "__MACOSX"
    )
    private val primaryNames = setOf("web", "www", "main", "site", "app", "frontend", "root", "home")
    private const val MAX_DEPTH = 4

    /** Real-file scan (after extraction). */
    fun scan(root: File): List<Found> {
        val out = ArrayList<Found>()
        fun walk(dir: File, rel: String, depth: Int) {
            val entry = entryOf(dir)
            if (entry != null && !isWorkspaceRoot(dir) && looksLikeServer(dir, rel, entry)) {
                out.add(Found(rel, entry))
                return
            }
            if (depth >= MAX_DEPTH) return
            dir.listFiles()?.filter { it.isDirectory && it.name !in skipDirs && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { walk(it, if (rel.isEmpty()) it.name else "$rel/${it.name}", depth + 1) }
        }
        walk(root, "", 0)
        return out
    }

    /** Sub-folders need proof (package.json or a listen() call) so a frontend folder with app.js is not a server. */
    private fun looksLikeServer(dir: File, rel: String, entry: String): Boolean {
        if (rel.isEmpty() || File(dir, "package.json").isFile) return true
        return try {
            val t = File(dir, entry).readText().take(60_000)
            t.contains(".listen(") || t.contains("createServer(") || t.contains("express(") || t.contains("fastify(")
        } catch (_: Exception) {
            false
        }
    }

    private fun isWorkspaceRoot(dir: File): Boolean = try {
        val pkg = File(dir, "package.json")
        pkg.isFile && JSONObject(pkg.readText()).has("workspaces")
    } catch (_: Exception) {
        false
    }

    fun entryOf(dir: File): String? {
        val pkg = File(dir, "package.json")
        if (pkg.isFile) {
            try {
                val json = JSONObject(pkg.readText())
                val start = json.optJSONObject("scripts")?.optString("start").orEmpty()
                val m = Regex("""(?:node|nodemon|bun)\s+(?:-{1,2}[\w-]+(?:=\S+)?\s+)*([^\s'";&|]+\.(?:m?js|cjs))""")
                    .find(start)?.groupValues?.getOrNull(1)
                if (m != null && safe(m) && File(dir, m).isFile) return m
                val main = json.optString("main").trim()
                if (main.isNotEmpty() && safe(main) && File(dir, main).isFile) return main
            } catch (_: Exception) {
            }
        }
        entryCandidates.firstOrNull { File(dir, it).isFile }?.let { return it }
        // Last resort for folders that have a package.json: any top-level .js that calls listen().
        if (pkg.isFile) return entryByListen(dir)
        return null
    }

    private fun entryByListen(dir: File): String? {
        val files = ArrayList<String>()
        dir.listFiles()?.filter { it.isFile }?.forEach { files.add(it.name) }
        File(dir, "src").listFiles()?.filter { it.isFile }?.forEach { files.add("src/" + it.name) }
        return files.sorted().firstOrNull { n ->
            (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs")) && try {
                val t = File(dir, n).readText().take(60_000)
                t.contains(".listen(")
            } catch (_: Exception) {
                false
            }
        }
    }

    /** ZIP-listing scan (before extraction). [names] are paths relative to the would-be project root. */
    fun scanNames(names: List<String>): List<Found> {
        val set = names.filter { !it.endsWith("/") }.toSet()
        val dirs = LinkedHashSet<String>()
        dirs.add("")
        for (n in set) {
            val parts = n.split('/')
            for (i in 1 until parts.size) {
                val d = parts.take(i)
                if (d.any { it in skipDirs || it.startsWith(".") } || d.size > MAX_DEPTH) break
                dirs.add(d.joinToString("/"))
            }
        }
        val out = ArrayList<Found>()
        val taken = ArrayList<String>()
        for (d in dirs.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))) {
            if (taken.any { t -> t.isEmpty() || d == t || d.startsWith("$t/") }) continue
            fun has(f: String) = (if (d.isEmpty()) f else "$d/$f") in set
            val entry = entryCandidates.firstOrNull { has(it) } ?: continue
            if (d.isNotEmpty() && !has("package.json") && entry != "server.js") continue
            out.add(Found(d, entry))
            taken.add(d)
        }
        return out
    }

    /** True when node_modules exists in [dir] or any parent up to [projectRoot]. */
    fun hasNodeModules(projectRoot: File, dir: File): Boolean {
        var cur: File? = dir
        val stop = projectRoot.canonicalFile
        while (cur != null) {
            if (File(cur, "node_modules").isDirectory) return true
            if (cur.canonicalFile == stop) break
            cur = cur.parentFile
        }
        return false
    }

    /**
     * Turns scan results into ServerDefs: unique names, subdomains from folder names, one primary,
     * fresh unique ports (never in [usedPorts], never 3101/3102, never [primaryPort] except the primary).
     */
    fun toDefs(found: List<Found>, projectName: String, usedPorts: Set<Int>, primaryPort: Int, startPort: Int): List<ServerDef> {
        if (found.isEmpty()) return emptyList()
        val primaryIdx = found.indexOfFirst { it.dir.isEmpty() }.takeIf { it >= 0 }
            ?: found.indexOfFirst { it.dir.substringAfterLast('/').lowercase() in primaryNames }.takeIf { it >= 0 }
            ?: 0
        val taken = HashSet<Int>(usedPorts + setOf(3101, 3102, primaryPort))
        val names = HashSet<String>()
        var next = startPort
        fun port(): Int {
            while (next in taken) next++
            return next.also { taken.add(it); next++ }
        }
        return found.mapIndexed { i, f ->
            val isPrimary = i == primaryIdx
            val base = sanitize(if (f.dir.isEmpty()) projectName else f.dir.substringAfterLast('/'))
            var nm = base
            var n = 2
            while (!names.add(nm)) nm = "$base-${n++}"
            ServerDef(
                name = nm,
                dir = f.dir,
                entry = f.entry,
                port = if (isPrimary) primaryPort else port(),
                // primary = apex (no route change); others -> <folder>.<domain>
                subdomain = if (isPrimary) "" else nm.replace('_', '-').replace('.', '-'),
                enabled = true,
                primary = isPrimary
            )
        }
    }

    private fun sanitize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-', '.').ifBlank { "server" }.take(32)

    private fun safe(p: String) = p.isNotBlank() && !p.startsWith("/") && !p.split('/').contains("..")
}
