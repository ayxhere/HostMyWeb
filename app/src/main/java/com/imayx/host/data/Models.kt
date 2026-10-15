package com.imayx.host.data

enum class RuntimeType { STATIC, NODE, SERVER }

enum class ProcessState { STOPPED, STARTING, RUNNING, FAILED }

enum class LogCategory(val label: String) {
    SERVER("Server"),
    CLOUDFLARE("Cloudflare"),
    NODE("Node"),
    SYSTEM("System"),
    ERRORS("Errors")
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val time: Long,
    val category: LogCategory,
    val level: LogLevel,
    val message: String
)

enum class ThemeMode { AMOLED, DARK, LIGHT }

data class ProjectConfig(
    val name: String,
    val runtime: RuntimeType = RuntimeType.STATIC,
    val port: Int = 3000,
    /** Sub-directory (relative to the project folder) that is served. Empty = project root. */
    val rootDir: String = "",
    val spa: Boolean = false,
    val domain: String = "",
    val cloudflareEnabled: Boolean = true,
    val autoStart: Boolean = false,
    val autoRestart: Boolean = false,
    /** Node only: entry script relative to the project folder. */
    val entryFile: String = "server.js",
    /** Node only: extra arguments (space separated). */
    val nodeArgs: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ProjectInfo(
    val config: ProjectConfig,
    val sizeBytes: Long,
    val fileCount: Int,
    val lastModified: Long
)

enum class TunnelMode { QUICK, NAMED }

/** Transport protocol for the cloudflared edge connection. */
enum class TunnelTransport { AUTO, QUIC, HTTP2 }

enum class TunnelStatus { DISCONNECTED, STARTING, CONNECTED, FAILED }

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val mode: TunnelMode? = null,
    val publicUrl: String? = null,
    val hostnames: List<String> = emptyList(),
    val tunnelId: String? = null,
    val connections: Int = 0,
    val edgeLocation: String? = null,
    val lastError: String? = null,
    val lastConnectedAt: Long = 0L,
    val startedAt: Long = 0L
)

data class HostState(
    val state: ProcessState = ProcessState.STOPPED,
    val project: String? = null,
    val runtime: RuntimeType = RuntimeType.STATIC,
    val port: Int = 3000,
    val error: String? = null,
    val startedAt: Long = 0L,
    val restarts: Int = 0,
    /** Additional projects hosted at the same time as [project] (multi-start). */
    val extras: List<String> = emptyList()
) {
    val localUrl: String get() = "http://127.0.0.1:$port"
}
