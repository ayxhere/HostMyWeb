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

    /** Untyped long storage for internal bookkeeping (AI cooldowns, etc.). No UI binding. */
    fun prefsLong(key: String, def: Long): Long = prefs.getLong(key, def)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
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

    /**
     * The DEFAULT port given to a bundle's main server when servers are detected
     * or a ZIP is imported (and the port reserved for it when handing out fresh
     * ports). It is NOT an override: every project binds its OWN saved port, and
     * the Cloudflare tunnel targets the hosted project's port.
     */
    var primaryPort: Int
        get() = prefs.getInt("primary_port", 3000)
        set(v) {
            prefs.edit().putInt("primary_port", v.coerceIn(1024, 65535)).apply()
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

    /** AI auto-fix: when a hosted server crashes, Groq diagnoses the logs and patches the project automatically. */
    var aiAutoFix: Boolean
        get() = prefs.getBoolean("ai_autofix", false)
        set(v) {
            prefs.edit().putBoolean("ai_autofix", v).apply()
            bump()
        }

    /** Groq model used by the AI auto-fixer. */
    var aiModel: String
        get() = prefs.getString("ai_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
        set(v) {
            prefs.edit().putString("ai_model", v.trim().ifBlank { "llama-3.3-70b-versatile" }).apply()
            bump()
        }

    /** Push Cloudflare tunnel routes + DNS for each server's subdomain automatically (named tunnel + API token). */
    var autoRoutes: Boolean
        get() = prefs.getBoolean("auto_routes", true)
        set(v) {
            prefs.edit().putBoolean("auto_routes", v).apply()
            bump()
        }

    /** Restart the tunnel by itself when the network / VPN changes. */
    var netWatch: Boolean
        get() = prefs.getBoolean("net_watch", true)
        set(v) {
            prefs.edit().putBoolean("net_watch", v).apply()
            bump()
        }

    // ---- PentAGI (remote Docker deployment, managed from the PentAGI tab) ----

    var pentagiEnabled: Boolean
        get() = prefs.getBoolean("pentagi_enabled", false)
        set(v) {
            prefs.edit().putBoolean("pentagi_enabled", v).apply()
            bump()
        }

    /** Public/private URL of the PentAGI web UI, e.g. https://vps.example.com:8443 */
    var pentagiRemoteUrl: String
        get() = prefs.getString("pentagi_remote_url", "") ?: ""
        set(v) {
            prefs.edit().putString("pentagi_remote_url", v.trim()).apply()
            bump()
        }

    /** URL of the pentagi-remote manager service, e.g. http://100.x.y.z:8787 (via Tailscale/SSH tunnel). */
    var pentagiManagerUrl: String
        get() = prefs.getString("pentagi_manager_url", "") ?: ""
        set(v) {
            prefs.edit().putString("pentagi_manager_url", v.trim()).apply()
            bump()
        }

    /** LLM provider id: openai | anthropic | gemini | deepseek | ollama | custom (see PentagiLlmProvider). */
    var pentagiLlmProvider: String
        get() = prefs.getString("pentagi_llm_provider", "openai") ?: "openai"
        set(v) {
            prefs.edit().putString("pentagi_llm_provider", v.trim().lowercase()).apply()
            bump()
        }

    /** PUBLIC_URL configured on the remote PentAGI instance (informational / setup checklist). */
    var pentagiPublicUrl: String
        get() = prefs.getString("pentagi_public_url", "") ?: ""
        set(v) {
            prefs.edit().putString("pentagi_public_url", v.trim()).apply()
            bump()
        }
}
