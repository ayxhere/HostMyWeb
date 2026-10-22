package com.imayx.host.cloudflare

import com.imayx.host.security.SecretStore

/**
 * Makes sure every (hostname -> local port) pair exists as a tunnel ingress route and a proxied CNAME.
 * Existing routes for other hostnames (e.g. the apex imayx.in -> :3000) are kept untouched;
 * a hostname that IS in the wanted list gets its stale target overwritten.
 */
object RouteSync {
    private val hostRx = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /** Routing facts for one running server, resolved by the caller. */
    data class ServerRouteInfo(
        /** "" or null = the primary server (apex / tunnel target; it has no subdomain of its own). */
        val subdomain: String?,
        val port: Int,
        /** The owning project's domain; blank = fall back to the settings default domain. */
        val baseDomain: String
    )

    /**
     * The full (hostname -> port) list a route sync must guarantee:
     *  - every server with a subdomain: `<sub>.<base>` -> its own port
     *    (base = the project's domain, else the settings default domain);
     *  - `api.<base>` for the API backend, by precedence: an explicit `api` server
     *    (api/ folder shape) always wins; otherwise the PRIMARY server — in the combined
     *    site+backend shape that one process serves the site AND /v1 on the tunnel-target
     *    port; otherwise the built-in AyX backend on [builtInApiPort], when it is enabled.
     * A hostname already claimed by an explicit server is never overwritten by the api rule.
     */
    fun wantedRoutes(
        servers: List<ServerRouteInfo>,
        defaultDomain: String,
        ayxBackendEnabled: Boolean,
        builtInApiPort: Int
    ): List<Pair<String, Int>> {
        fun baseOf(info: ServerRouteInfo): String =
            info.baseDomain.ifBlank { defaultDomain }.trim().lowercase()
        val wanted = LinkedHashMap<String, Int>()
        for (s in servers) {
            val sub = s.subdomain?.trim()?.lowercase().orEmpty()
            if (sub.isEmpty()) continue
            val base = baseOf(s)
            if (base.isEmpty()) continue
            wanted.putIfAbsent("$sub.$base", s.port)
        }
        val primary = servers.firstOrNull { it.subdomain.isNullOrBlank() }
        val apiBase: String
        val apiPort: Int
        when {
            primary != null -> {
                apiBase = baseOf(primary)
                apiPort = primary.port
            }
            ayxBackendEnabled -> {
                apiBase = defaultDomain.trim().lowercase()
                apiPort = builtInApiPort
            }
            else -> {
                apiBase = ""
                apiPort = -1
            }
        }
        if (apiBase.isNotEmpty() && apiPort > 0) wanted.putIfAbsent("api.$apiBase", apiPort)
        return wanted.entries.map { it.key to it.value }
    }

    fun sync(api: CloudflareApi, secrets: SecretStore, wanted: List<Pair<String, Int>>): String {
        val tok = secrets.get(SecretStore.KEY_TUNNEL_TOKEN)?.trim().orEmpty()
        if (tok.isEmpty()) return "Route sync skipped: no tunnel token."
        val parsed = TunnelToken.parse(TunnelToken.canonicalize(tok) ?: tok)
            ?: return "Route sync skipped: tunnel token not valid."
        if (secrets.get(SecretStore.KEY_API_TOKEN).isNullOrBlank()) {
            return "Route sync skipped: add a Cloudflare API token (Settings) to auto-create api./hello. routes."
        }
        val items = wanted.map { it.first.lowercase() to it.second }.filter { hostRx.matches(it.first) }
        if (items.isEmpty()) return "Route sync skipped: no valid hostnames."

        val cur = api.getIngress(parsed.accountId, parsed.tunnelId)
        if (!cur.ok) return "Route sync failed (read): ${cur.error}"
        val existing = cur.data.orEmpty().filter { !it.hostname.isNullOrBlank() }
        val ours = items.map { it.first }.toSet()
        val desired = existing.filter { it.hostname!!.lowercase() !in ours } +
            items.map { IngressRoute(it.first, "http://127.0.0.1:${it.second}") }
        val same = desired.size == existing.size &&
            desired.all { d -> existing.any { e -> e.hostname.equals(d.hostname, true) && e.service == d.service } }

        val lines = ArrayList<String>()
        if (same) {
            lines.add("Routes already up to date (${items.size}).")
        } else {
            val put = api.putIngress(parsed.accountId, parsed.tunnelId, desired)
            if (!put.ok) return "Route sync failed (write): ${put.error}"
            lines.add("Routes saved: " + items.joinToString { "${it.first} → :${it.second}" })
        }
        val zones = HashMap<String, String>()
        for ((host, _) in items) {
            val base = host.split('.').takeLast(2).joinToString(".")
            val zone = zones[base] ?: run {
                val z = api.findZoneId(host)
                if (!z.ok) {
                    lines.add("DNS $host: ${z.error}")
                    return@run null
                }
                z.data!!.also { zones[base] = it }
            } ?: continue
            val r = api.upsertTunnelCname(zone, host, parsed.tunnelId)
            lines.add(if (r.ok) r.data.orEmpty() else "DNS $host: ${r.error}")
        }
        return lines.joinToString("\n")
    }
}
