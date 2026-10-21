package com.imayx.host.pentagi

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

/**
 * HTTP client for the pentagi-remote manager (and a reachability probe for
 * the PentAGI web UI). Uses only HttpURLConnection — the project has zero
 * networking dependencies and this integration adds none.
 *
 * Security:
 *  - The manager token comes from SecretStore at call time and is sent only
 *    as `Authorization: Bearer …`. It is never logged and never stored here.
 *  - Action paths are built ONLY from [PentagiAction] via [PentagiValidator.actionPath].
 *  - Response bodies are capped at 256 KB. Timeouts: 5 s connect / 15 s read.
 *  - All failures are returned as typed [Result] values — nothing throws to the UI.
 *  - The PentAGI probe uses the platform default TLS trust. A TLS failure is
 *    reported distinctly ("TLS error") instead of being silently accepted:
 *    PentAGI ships with a self-signed cert by default, so the user may need to
 *    put it behind a reverse proxy with a real cert, or accept that the probe
 *    reports TLS error while the manager's server-side probe still works.
 */
class PentagiClient(
    private val managerBaseUrl: () -> String,
    private val managerToken: () -> String?,
    private val pentagiBaseUrl: () -> String
) {
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String, val httpCode: Int = -1) : Result<Nothing>()
    }

    data class ManagerHealth(val ok: Boolean, val docker: Boolean, val compose: Boolean, val raw: String)
    data class ManagerStatus(
        val composeState: String,
        val services: Map<String, String>,
        val pentagiReachable: Boolean,
        val raw: String
    )
    data class ConfigStatus(val envKeysSet: List<String>, val raw: String)

    private fun readCapped(input: InputStream?): String {
        if (input == null) return ""
        val bytes = ByteArray(256 * 1024)
        var total = 0
        input.use { stream ->
            while (total < bytes.size) {
                val n = stream.read(bytes, total, bytes.size - total)
                if (n <= 0) break
                total += n
            }
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun request(
        method: String,
        urlString: String,
        bearer: String?,
        body: ByteArray? = null
    ): Result<Pair<Int, String>> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = readCapped(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code in 200..299) Result.Ok(code to text)
            else Result.Err("HTTP $code", code)
        } catch (e: SSLException) {
            Result.Err("TLS error: ${e.message ?: "certificate validation failed"}")
        } catch (e: Exception) {
            // Never include the URL query or headers (token) in the message.
            Result.Err(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun managerUrl(path: String): Result<String> {
        val base = managerBaseUrl().trim()
        val err = PentagiValidator.validateManagerUrl(base)
        if (err != null) return Result.Err(err)
        return Result.Ok(PentagiValidator.normalizeBaseUrl(base) + path)
    }

    private fun tokenOrErr(): Result<String> {
        val t = managerToken()?.trim().orEmpty()
        if (t.isEmpty()) return Result.Err("Manager token is not set")
        return Result.Ok(t)
    }

    fun health(): Result<ManagerHealth> {
        val url = when (val u = managerUrl("/v1/health")) { is Result.Ok -> u.value; is Result.Err -> return u }
        val tok = when (val t = tokenOrErr()) { is Result.Ok -> t.value; is Result.Err -> return t }
        return when (val r = request("GET", url, tok)) {
            is Result.Err -> r
            is Result.Ok -> try {
                val o = JSONObject(r.value.second)
                Result.Ok(ManagerHealth(o.optBoolean("ok"), o.optBoolean("docker"), o.optBoolean("compose"), r.value.second))
            } catch (_: Exception) {
                Result.Err("Manager returned invalid JSON")
            }
        }
    }

    fun status(): Result<ManagerStatus> {
        val url = when (val u = managerUrl("/v1/status")) { is Result.Ok -> u.value; is Result.Err -> return u }
        val tok = when (val t = tokenOrErr()) { is Result.Ok -> t.value; is Result.Err -> return t }
        return when (val r = request("GET", url, tok)) {
            is Result.Err -> r
            is Result.Ok -> try {
                val o = JSONObject(r.value.second)
                val services = LinkedHashMap<String, String>()
                val so = o.optJSONObject("services")
                if (so != null) {
                    val it = so.keys()
                    while (it.hasNext()) { val k = it.next(); services[k] = so.optString(k, "unknown") }
                }
                Result.Ok(
                    ManagerStatus(
                        composeState = o.optString("composeState", "unknown"),
                        services = services,
                        pentagiReachable = o.optBoolean("pentagiReachable"),
                        raw = r.value.second
                    )
                )
            } catch (_: Exception) {
                Result.Err("Manager returned invalid JSON")
            }
        }
    }

    /** POST /v1/actions/{start|stop|restart} — path from the enum only, empty JSON body. */
    fun action(action: PentagiAction): Result<String> {
        val url = when (val u = managerUrl(PentagiValidator.actionPath(action))) { is Result.Ok -> u.value; is Result.Err -> return u }
        val tok = when (val t = tokenOrErr()) { is Result.Ok -> t.value; is Result.Err -> return t }
        return when (val r = request("POST", url, tok, "{}".toByteArray(Charsets.UTF_8))) {
            is Result.Err -> r
            is Result.Ok -> Result.Ok(r.value.second)
        }
    }

    fun logs(tail: Int): Result<String> {
        val n = PentagiValidator.clampTail(tail)
        val url = when (val u = managerUrl("/v1/logs?tail=$n")) { is Result.Ok -> u.value; is Result.Err -> return u }
        val tok = when (val t = tokenOrErr()) { is Result.Ok -> t.value; is Result.Err -> return t }
        return when (val r = request("GET", url, tok)) {
            is Result.Err -> r
            is Result.Ok -> try {
                val o = JSONObject(r.value.second)
                Result.Ok(o.optString("logs", r.value.second))
            } catch (_: Exception) {
                Result.Ok(r.value.second)
            }
        }
    }

    /** Which official env keys are SET on the remote (names only, never values). */
    fun configStatus(): Result<ConfigStatus> {
        val url = when (val u = managerUrl("/v1/config-status")) { is Result.Ok -> u.value; is Result.Err -> return u }
        val tok = when (val t = tokenOrErr()) { is Result.Ok -> t.value; is Result.Err -> return t }
        return when (val r = request("GET", url, tok)) {
            is Result.Err -> r
            is Result.Ok -> try {
                val o = JSONObject(r.value.second)
                val arr = o.optJSONArray("envKeysSet")
                val keys = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) keys.add(arr.optString(i))
                Result.Ok(ConfigStatus(keys, r.value.second))
            } catch (_: Exception) {
                Result.Err("Manager returned invalid JSON")
            }
        }
    }

    /**
     * Direct reachability probe of the PentAGI web UI from this device.
     * Any HTTP response (even 401/404/redirect) counts as reachable.
     * TLS failures are reported distinctly. No credentials are sent.
     */
    fun probePentagi(): Result<Boolean> {
        val base = pentagiBaseUrl().trim()
        val err = PentagiValidator.validateRemoteUrl(base)
        if (err != null) return Result.Err(err)
        return when (val r = request("GET", PentagiValidator.normalizeBaseUrl(base) + "/", null)) {
            is Result.Ok -> Result.Ok(true)
            is Result.Err -> if (r.httpCode > 0) Result.Ok(true) else r
        }
    }
}
