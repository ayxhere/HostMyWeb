package com.imayx.host.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (tunnel token, API token, Node env vars) are encrypted with an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it. Only ciphertext is written to app-private prefs.
 * Values are never logged, never put in BuildConfig, and never exported with backups.
 */
class SecretStore(context: Context) {

    companion object {
        const val KEY_TUNNEL_TOKEN = "cf_tunnel_token"
        const val KEY_API_TOKEN = "cf_api_token"
        const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "imayx_host_secrets_v1"
        private const val ENV_PREFIX = "node_env."
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("ix_secrets", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        return try {
            val parts = stored.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Returns false if the Keystore refused the write (value is then NOT stored). */
    fun put(name: String, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) {
            remove(name)
            return true
        }
        return try {
            prefs.edit().putString(name, encrypt(v)).apply()
            Redactor.register(v)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val v = decrypt(stored)
        if (v != null) Redactor.register(v)
        return v
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) {
        val old = get(name)
        prefs.edit().remove(name).apply()
        Redactor.unregister(old)
    }

    /** Node environment secrets, one encrypted JSON blob per project. */
    fun getEnv(project: String): Map<String, String> {
        val raw = get(ENV_PREFIX + project) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optString(k, "")
                map[k] = v
                Redactor.register(v)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setEnv(project: String, env: Map<String, String>) {
        if (env.isEmpty()) {
            remove(ENV_PREFIX + project)
            return
        }
        val o = JSONObject()
        for ((k, v) in env) o.put(k, v)
        put(ENV_PREFIX + project, o.toString())
    }

    fun moveEnv(from: String, to: String) {
        val env = getEnv(from)
        remove(ENV_PREFIX + from)
        if (env.isNotEmpty()) setEnv(to, env)
    }

    fun removeEnv(project: String) = remove(ENV_PREFIX + project)

    /** Register every stored secret with the log redactor (call once at app start). */
    fun registerAllForRedaction() {
        for (key in prefs.all.keys) get(key)
        // Env blobs are JSON; make sure individual values are registered too.
        for (key in prefs.all.keys) {
            if (key.startsWith(ENV_PREFIX)) getEnv(key.removePrefix(ENV_PREFIX))
        }
    }
}
