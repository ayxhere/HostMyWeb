package com.imayx.host.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-secret app settings. Secrets live in SecretStore (Android Keystore). */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ix_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeMode> = _theme

    private val _version = MutableStateFlow(0)

    /** Bumped on every write so ViewModels can re-read. */
    val version: StateFlow<Int> = _version

    private fun readTheme(): ThemeMode = try {
        ThemeMode.valueOf(prefs.getString("theme", ThemeMode.AMOLED.name) ?: ThemeMode.AMOLED.name)
    } catch (_: Exception) {
        ThemeMode.AMOLED
    }

    private fun bump() {
        _version.value = _version.value + 1
    }

    var themeMode: ThemeMode
        get() = _theme.value
        set(v) {
            prefs.edit().putString("theme", v.name).apply()
            _theme.value = v
            bump()
        }

    var defaultPort: Int
        get() = prefs.getInt("default_port", 3000)
        set(v) {
            prefs.edit().putInt("default_port", v.coerceIn(1024, 65535)).apply()
            bump()
        }

    /** Project used by Start Host when none is chosen explicitly. */
    var activeProject: String
        get() = prefs.getString("active_project", "") ?: ""
        set(v) {
            prefs.edit().putString("active_project", v).apply()
            bump()
        }

    var quickTunnel: Boolean
        get() = prefs.getBoolean("quick_tunnel", true)
        set(v) {
            prefs.edit().putBoolean("quick_tunnel", v).apply()
            bump()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", false)
        set(v) {
            prefs.edit().putBoolean("start_on_boot", v).apply()
            bump()
        }

    /** True while the user wants hosting running (used to resume after process death / reboot). */
    var hostingWanted: Boolean
        get() = prefs.getBoolean("hosting_wanted", false)
        set(v) {
            prefs.edit().putBoolean("hosting_wanted", v).apply()
        }

    var autosave: Boolean
        get() = prefs.getBoolean("autosave", false)
        set(v) {
            prefs.edit().putBoolean("autosave", v).apply()
            bump()
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) {
            prefs.edit().putBoolean("notifications", v).apply()
            bump()
        }

    /** Run the embedded AyX Group backend (Node.js + SQLite, in-app) alongside hosting. */
    var ayxBackendEnabled: Boolean
        get() = prefs.getBoolean("ayx_backend", true)
        set(v) {
            prefs.edit().putBoolean("ayx_backend", v).apply()
            bump()
        }

    /** Optional override for the cloudflared download URL (e.g. an Android-compatible build). */
    var cloudflaredUrl: String
        get() = prefs.getString("cloudflared_url", "") ?: ""
        set(v) {
            prefs.edit().putString("cloudflared_url", v.trim()).apply()
            bump()
        }

    /** QUIC/UDP vs HTTP/2 transport for the Cloudflare tunnel edge connection. */
    var tunnelTransport: TunnelTransport
        get() = try {
            TunnelTransport.valueOf(prefs.getString("tunnel_transport", TunnelTransport.AUTO.name) ?: TunnelTransport.AUTO.name)
        } catch (_: Exception) {
            TunnelTransport.AUTO
        }
        set(v) {
            prefs.edit().putString("tunnel_transport", v.name).apply()
            bump()
        }

    /** URL of an Android-compatible ARM64 Node.js runtime archive/binary. */
    var nodeRuntimeUrl: String
        get() = prefs.getString("node_runtime_url", "") ?: ""
        set(v) {
            prefs.edit().putString("node_runtime_url", v.trim()).apply()
            bump()
        }

    /** Domain shown in the UI/route editor when a project has none (e.g. imayx.in). */
    var defaultDomain: String
        get() = prefs.getString("default_domain", "") ?: ""
        set(v) {
            prefs.edit().putString("default_domain", v.trim()).apply()
            bump()
        }
}
