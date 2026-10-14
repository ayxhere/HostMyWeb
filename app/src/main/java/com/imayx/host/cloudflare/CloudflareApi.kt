package com.imayx.host.cloudflare

import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiResult<T>(val ok: Boolean, val data: T?, val error: String?)

data class IngressRoute(val hostname: String?, val service: String)

/**
 * Minimal Cloudflare REST client. It uses the separate **API Token** (never the tunnel token) and only
 * runs when the user presses a button. Results reflect exactly what the API answered.
 * The token is sent only in the Authorization header and is never logged.
 */
class CloudflareApi(private val tokenProvider: () -> String?) {

    private val base = "https://api.cloudflare.com/client/v4"

    private fun call(method: String, path: String, body: JSONObject? = null): ApiResult<JSONObject> {
        val token = tokenProvider()
        if (token.isNullOrBlank()) return ApiResult(false, null, "No Cloudflare API Token configured (Settings → Cloudflare).")
        return try {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer " + token.trim())
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "IMAYX-Host")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return ApiResult(false, null, "HTTP $code: unexpected response from Cloudflare")
            }
            if (json.optBoolean("success", false)) {
                ApiResult(true, json, null)
            } else {
                ApiResult(false, json, describeErrors(json, code))
            }
        } catch (e: Exception) {
            ApiResult(false, null, "Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun describeErrors(json: JSONObject, code: Int): String {
        val errs = json.optJSONArray("errors")
        if (errs != null && errs.length() > 0) {
            val parts = ArrayList<String>()
            for (i in 0 until errs.length()) {
                val e = errs.optJSONObject(i) ?: continue
                parts.add(e.optString("message", "error") + " (code " + e.optInt("code") + ")")
            }
            if (parts.isNotEmpty()) return "HTTP $code: " + parts.joinToString("; ")
        }
        return "HTTP $code"
    }

    /**
     * Account-owned tokens (dash → Manage Account → Account API tokens) verify at /accounts/{id}/tokens/verify,
     * user tokens at /user/tokens/verify. Try the account endpoint first when the account id is known.
     */
    fun verifyToken(accountId: String? = null): ApiResult<String> {
        var r = if (!accountId.isNullOrBlank()) call("GET", "/accounts/$accountId/tokens/verify") else call("GET", "/user/tokens/verify")
        if (!r.ok && !accountId.isNullOrBlank()) r = call("GET", "/user/tokens/verify")
        if (!r.ok) return ApiResult(false, null, r.error)
        val status = r.data?.optJSONObject("result")?.optString("status", "unknown") ?: "unknown"
        return ApiResult(status == "active", status, if (status == "active") null else "Token status: $status")
    }

    fun getIngress(accountId: String, tunnelId: String): ApiResult<List<IngressRoute>> {
        val r = call("GET", "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations")
        if (!r.ok) return ApiResult(false, null, r.error)
        val ingress = r.data?.optJSONObject("result")?.optJSONObject("config")?.optJSONArray("ingress")
        val routes = ArrayList<IngressRoute>()
        if (ingress != null) {
            for (i in 0 until ingress.length()) {
                val o = ingress.optJSONObject(i) ?: continue
                val host = if (o.has("hostname")) o.optString("hostname") else null
                routes.add(IngressRoute(host, o.optString("service", "")))
            }
        }
        return ApiResult(true, routes, null)
    }

    /** Replaces the tunnel's published routes. A catch-all http_status:404 rule is appended as Cloudflare requires. */
    fun putIngress(accountId: String, tunnelId: String, routes: List<IngressRoute>): ApiResult<Unit> {
        val arr = JSONArray()
        for (r in routes) {
            if (r.hostname.isNullOrBlank()) continue
            arr.put(JSONObject().put("hostname", r.hostname).put("service", r.service))
        }
        arr.put(JSONObject().put("service", "http_status:404"))
        val body = JSONObject().put("config", JSONObject().put("ingress", arr))
        val r = call("PUT", "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations", body)
        if (r.ok) LogBus.i(LogCategory.CLOUDFLARE, "Tunnel routes updated via API (${routes.size} route(s))")
        return ApiResult(r.ok, Unit.takeIf { r.ok }, r.error)
    }

    /** Finds the zone whose name is the longest suffix of [hostname]. */
    fun findZoneId(hostname: String): ApiResult<String> {
        val labels = hostname.lowercase().trim('.').split('.')
        var lastError: String? = null
        for (i in 0 until (labels.size - 1)) {
            val candidate = labels.drop(i).joinToString(".")
            val r = call("GET", "/zones?name=$candidate&status=active")
            if (!r.ok) {
                lastError = r.error
                continue
            }
            val result = r.data?.optJSONArray("result")
            if (result != null && result.length() > 0) {
                val id = result.getJSONObject(0).optString("id")
                if (id.isNotBlank()) return ApiResult(true, id, null)
            }
        }
        return ApiResult(false, null, lastError ?: "No active Cloudflare zone found for $hostname on this account.")
    }

    /** Creates or updates the proxied CNAME that points [hostname] at the tunnel. */
    fun upsertTunnelCname(zoneId: String, hostname: String, tunnelId: String): ApiResult<String> {
        val target = "$tunnelId.cfargotunnel.com"
        val existing = call("GET", "/zones/$zoneId/dns_records?name=$hostname")
        if (!existing.ok) return ApiResult(false, null, existing.error)
        val arr = existing.data?.optJSONArray("result")
        val payload = JSONObject()
            .put("type", "CNAME")
            .put("name", hostname)
            .put("content", target)
            .put("proxied", true)
            .put("ttl", 1)
        if (arr != null && arr.length() > 0) {
            val rec = arr.getJSONObject(0)
            val type = rec.optString("type")
            if (type != "CNAME") {
                return ApiResult(false, null, "A $type record already exists for $hostname. Remove it in the Cloudflare dashboard first.")
            }
            val id = rec.optString("id")
            val r = call("PUT", "/zones/$zoneId/dns_records/$id", payload)
            return if (r.ok) ApiResult(true, "Updated CNAME $hostname → $target", null) else ApiResult(false, null, r.error)
        }
        val r = call("POST", "/zones/$zoneId/dns_records", payload)
        return if (r.ok) ApiResult(true, "Created CNAME $hostname → $target", null) else ApiResult(false, null, r.error)
    }
}
