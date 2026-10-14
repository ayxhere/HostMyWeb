package com.imayx.host.security

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Removes secrets from any text before it is logged, displayed, copied or exported.
 * Known secret values are registered at runtime (see SecretStore); generic patterns catch the rest.
 */
object Redactor {
    private const val MASK = "[REDACTED]"
    private val known = CopyOnWriteArraySet<String>()

    private val tokenBlob = Regex("eyJ[A-Za-z0-9_\\-+/=]{30,}")
    private val keyValue = Regex(
        "(?i)(token|secret|password|passwd|api[_-]?key|authorization|bearer)([\"'\\s:=]+)([^\\s\"',;]{12,})"
    )
    private val privateKey = Regex(
        "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"
    )
    private val tokenArg = Regex("--token\\s+\\S+")

    fun register(secret: String?) {
        if (secret == null) return
        val s = secret.trim()
        if (s.length >= 6) known.add(s)
    }

    fun unregister(secret: String?) {
        if (secret == null) return
        known.remove(secret.trim())
    }

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        for (k in known) {
            if (out.contains(k)) out = out.replace(k, MASK)
        }
        out = privateKey.replace(out, MASK)
        out = tokenArg.replace(out, "--token $MASK")
        out = tokenBlob.replace(out, MASK)
        out = keyValue.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        return out
    }

    /** Masked preview for UI, e.g. "eyJh…x9Qw". Never returns the full secret. */
    fun mask(secret: String): String {
        if (secret.length <= 10) return "••••••••"
        return secret.take(4) + "••••••••" + secret.takeLast(2)
    }
}
