package com.imayx.host.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local app lock. The PIN is independent of any Cloudflare credential and never leaves the device.
 * Only a salted PBKDF2 hash is stored.
 */
class AppLock(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ix_lock", Context.MODE_PRIVATE)

    private val _unlocked = MutableStateFlow(!isEnabled())
    val unlocked: StateFlow<Boolean> = _unlocked

    @Volatile
    private var sensitiveUntil = 0L

    @Volatile
    private var failedAttempts = 0

    @Volatile
    private var blockedUntil = 0L

    fun hasPin(): Boolean = prefs.contains("hash")

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false) && hasPin()

    fun isBiometricEnabled(): Boolean = prefs.getBoolean("biometric", false)

    fun setBiometricEnabled(v: Boolean) {
        prefs.edit().putBoolean("biometric", v).apply()
    }

    private fun algorithm(): String =
        if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun hash(pin: String, salt: ByteArray, alg: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val alg = algorithm()
        val h = hash(pin, salt, alg)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(h, Base64.NO_WRAP))
            .putString("alg", alg)
            .putBoolean("enabled", true)
            .apply()
        _unlocked.value = true
    }

    /** Returns remaining block seconds (>0) if too many wrong attempts, else 0. */
    fun blockedSeconds(): Long {
        val left = blockedUntil - SystemClock.elapsedRealtime()
        return if (left > 0) (left + 999) / 1000 else 0
    }

    fun verifyPin(pin: String): Boolean {
        if (blockedSeconds() > 0) return false
        val salt = prefs.getString("salt", null) ?: return false
        val stored = prefs.getString("hash", null) ?: return false
        val alg = prefs.getString("alg", algorithm()) ?: algorithm()
        val ok = try {
            val expected = Base64.decode(stored, Base64.NO_WRAP)
            val actual = hash(pin, Base64.decode(salt, Base64.NO_WRAP), alg)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            failedAttempts = 0
        } else {
            failedAttempts++
            if (failedAttempts >= 5) {
                blockedUntil = SystemClock.elapsedRealtime() + 30_000
                failedAttempts = 0
            }
        }
        return ok
    }

    fun disable() {
        prefs.edit().clear().apply()
        _unlocked.value = true
    }

    fun lock() {
        if (isEnabled()) _unlocked.value = false
        sensitiveUntil = 0L
    }

    fun markUnlocked() {
        _unlocked.value = true
    }

    /** Sensitive screens (credentials) re-prompt after 2 minutes. */
    fun isSensitiveUnlocked(): Boolean {
        if (!isEnabled()) return true
        return SystemClock.elapsedRealtime() < sensitiveUntil
    }

    fun markSensitiveUnlocked() {
        sensitiveUntil = SystemClock.elapsedRealtime() + 120_000
    }
}
