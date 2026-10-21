package com.imayx.host.pentagi

import java.net.URI

/**
 * Pure-Kotlin PentAGI models + validation. NO Android imports so this file
 * compiles and unit-tests standalone with kotlinc on any JVM.
 *
 * PentAGI (github.com/vxcontrol/pentagi) fundamentally requires a real Docker
 * daemon on Linux: it spawns sandbox containers with NET_ADMIN/NET_RAW and
 * mounts /var/run/docker.sock. It cannot run on Android / Termux proot.
 * HostMyWeb therefore only supports REMOTE deployments; LOCAL is explicitly
 * UNSUPPORTED and the UI must say so honestly.
 */

enum class PentagiDeployMode {
    NOT_CONFIGURED,
    REMOTE,
    LOCAL_UNSUPPORTED
}

/** Allowlisted actions. HTTP paths are built ONLY from these enum names. */
enum class PentagiAction {
    START, STOP, RESTART;

    /** e.g. START -> "start" — used as the final path segment of /v1/actions/{action}. */
    fun pathSegment(): String = name.lowercase()

    companion object {
        fun fromPathSegment(s: String): PentagiAction? =
            entries.firstOrNull { it.pathSegment() == s }
    }
}

/** LLM providers supported by official PentAGI, mapped to official .env key names. */
enum class PentagiLlmProvider(val id: String, val label: String, val envKey: String) {
    OPENAI("openai", "OpenAI", "OPEN_AI_KEY"),
    ANTHROPIC("anthropic", "Anthropic", "ANTHROPIC_API_KEY"),
    GEMINI("gemini", "Gemini", "GEMINI_API_KEY"),
    DEEPSEEK("deepseek", "DeepSeek", "DEEPSEEK_API_KEY"),
    OLLAMA("ollama", "Ollama (local)", "OLLAMA_SERVER_URL"),
    CUSTOM("custom", "Custom / OpenAI-compatible", "LLM_SERVER_KEY");

    companion object {
        fun fromId(id: String?): PentagiLlmProvider =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: OPENAI

        /** All official LLM-related env key names (allowlist for config-status). */
        val ALL_ENV_KEYS: List<String> = listOf(
            "OPEN_AI_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY",
            "BEDROCK_ACCESS_KEY_ID", "BEDROCK_SECRET_ACCESS_KEY",
            "OLLAMA_SERVER_URL", "OLLAMA_SERVER_MODEL",
            "LLM_SERVER_URL", "LLM_SERVER_KEY", "LLM_SERVER_MODEL",
            "DATABASE_URL", "COOKIE_SIGNING_SALT", "PUBLIC_URL", "CORS_ORIGINS",
            "PENTAGI_POSTGRES_USER", "PENTAGI_POSTGRES_PASSWORD", "PENTAGI_POSTGRES_DB",
            "SERVER_PORT", "PENTAGI_LISTEN_IP", "PENTAGI_LISTEN_PORT"
        )
    }
}

data class PentagiHealth(
    val status: String = "NOT_CONFIGURED",
    val pentagiReachable: Boolean = false,
    val managerReachable: Boolean = false,
    val composeState: String = "unknown",
    val detail: String = "",
    val checkedAt: Long = 0L,
    val mode: PentagiDeployMode = PentagiDeployMode.NOT_CONFIGURED
) {
    companion object {
        fun notConfigured(detail: String): PentagiHealth = PentagiHealth(
            status = "NOT_CONFIGURED",
            detail = detail,
            checkedAt = System.currentTimeMillis(),
            mode = PentagiDeployMode.NOT_CONFIGURED
        )

        fun localUnsupported(): PentagiHealth = PentagiHealth(
            status = "LOCAL_UNSUPPORTED",
            detail = "PentAGI needs a Docker daemon on Linux (docker.sock sandboxes, NET_ADMIN, Postgres/pgvector). " +
                "It cannot run on Android / Termux proot. Configure a remote Linux host instead.",
            checkedAt = System.currentTimeMillis(),
            mode = PentagiDeployMode.LOCAL_UNSUPPORTED
        )
    }
}

/**
 * Pure validation — every user-supplied value that reaches an HTTP request
 * or the manager API passes through here first. No shell is ever involved
 * on the Android side; on the remote side the manager uses execFile with
 * fixed argv arrays, so these checks are defence-in-depth, not the only wall.
 */
object PentagiValidator {

    private val FORBIDDEN_CHARS = charArrayOf(' ', '\t', '\n', '\r', ';', '|', '&', '`', '$', '(', ')', '<', '>', '\\', '"', '\'')

    private fun hasForbidden(raw: String): Boolean = raw.any { it in FORBIDDEN_CHARS || it.code < 0x20 }

    /**
     * Validates a base URL (manager or PentAGI UI).
     * Rules: non-blank, no whitespace/shell chars, parses as URI, scheme http/https,
     * host non-blank, no userinfo (user:pass@), no fragment.
     * Returns null when valid, otherwise a user-facing error string.
     */
    fun validateUrl(raw: String, label: String = "URL"): String? {
        val v = raw.trim()
        if (v.isEmpty()) return "$label is empty"
        if (hasForbidden(v)) return "$label contains invalid characters"
        val uri = try {
            URI(v)
        } catch (_: Exception) {
            return "$label is not a valid URL"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "$label must start with http:// or https://"
        if (uri.host.isNullOrBlank()) return "$label has no host"
        if (uri.userInfo != null) return "$label must not contain credentials (userinfo)"
        if (uri.fragment != null) return "$label must not contain a fragment (#…)"
        return null
    }

    fun validateManagerUrl(raw: String): String? = validateUrl(raw, "Manager URL")
    fun validateRemoteUrl(raw: String): String? = validateUrl(raw, "PentAGI URL")

    fun isValidUrl(raw: String): Boolean = validateUrl(raw) == null

    /** Normalises a base URL: trim + strip trailing slashes. Call only after validation. */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    fun validatePort(port: Int): String? =
        if (port in 1024..65535) null else "Port must be between 1024 and 65535"

    fun isValidPort(port: Int): Boolean = port in 1024..65535

    fun validateTail(tail: Int): String? =
        if (tail in 1..500) null else "Log tail must be between 1 and 500"

    fun isValidTail(tail: Int): Boolean = tail in 1..500

    fun clampTail(tail: Int): Int = tail.coerceIn(1, 500)

    /**
     * Light sanity check for a provider API key: non-blank, reasonable length,
     * no whitespace / shell metacharacters. Never logs or returns the key.
     */
    fun isValidProviderKey(key: String): Boolean {
        val k = key.trim()
        if (k.length < 8 || k.length > 512) return false
        if (hasForbidden(k)) return false
        return true
    }

    fun validateProviderKey(key: String): String? =
        if (isValidProviderKey(key)) null else "API key looks invalid (8–512 chars, no spaces or shell characters)"

    /** Manager token: same rules, minimum 24 chars (mirrors the Node manager). */
    fun isValidManagerToken(token: String): Boolean {
        val t = token.trim()
        return t.length in 24..512 && !hasForbidden(t)
    }

    /** Builds the manager action path ONLY from the enum — never from user input. */
    fun actionPath(action: PentagiAction): String = "/v1/actions/" + action.pathSegment()
}
