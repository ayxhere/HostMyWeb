package com.imayx.host.runtime

import java.io.File
import java.io.IOException

/**
 * Wraps one child process: captures stdout/stderr line by line, reports exit, and guarantees the whole
 * process tree is killed on [stop] (no orphans). Pids are recorded in [ProcessRegistry] so leftovers can be
 * reaped after the app itself was killed.
 */
class ManagedProcess(
    private val command: List<String>,
    private val workDir: File?,
    private val env: Map<String, String>,
    private val registry: ProcessRegistry?,
    private val registryMarker: String,
    private val onLine: (line: String, isError: Boolean) -> Unit,
    private val onExit: (exitCode: Int, stopRequested: Boolean) -> Unit
) {
    private var process: Process? = null

    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    private var stopRequested = false

    @Throws(IOException::class)
    fun start() {
        val pb = ProcessBuilder(command)
        if (workDir != null) pb.directory(workDir)
        pb.environment().putAll(env)
        val p = pb.start()
        process = p
        pid = ProcessUtil.pidOf(p)
        registry?.register(pid, registryMarker)

        val out = Thread({ pump(p.inputStream, false) }, "ix-proc-out")
        val err = Thread({ pump(p.errorStream, true) }, "ix-proc-err")
        out.isDaemon = true
        err.isDaemon = true
        out.start()
        err.start()

        val waiter = Thread({
            var code = -1
            try {
                code = p.waitFor()
            } catch (_: InterruptedException) {
            }
            try {
                out.join(1500)
                err.join(1500)
            } catch (_: InterruptedException) {
            }
            // The main process is gone: make sure none of its children survive.
            registry?.unregister(pid)
            onExit(code, stopRequested)
        }, "ix-proc-wait")
        waiter.isDaemon = true
        waiter.start()
    }

    private fun pump(stream: java.io.InputStream, isError: Boolean) {
        try {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isNotBlank()) onLine(line, isError)
                }
            }
        } catch (_: IOException) {
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun stop() {
        val p = process ?: return
        stopRequested = true
        val myPid = pid
        if (myPid > 0) {
            ProcessUtil.killTree(myPid)
        }
        try {
            if (p.isAlive) p.destroy()
            if (p.isAlive) p.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}
