package ru.foric27.cluster

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Выполнение privileged shell-команд только через `su`.
 */
object RootShell {

    private const val TAG = "RootShell"
    const val ROOT_REQUIRED_WARNING = "Для этой операции нужен root через su"

    enum class AccessMode {
        NONE,
        SU,
    }

    @Volatile private var rootUnavailableCached = false
    private val availableToolCache = HashSet<String>()
    private val missingToolCache = HashSet<String>()

    data class Result(
        val code: Int,
        val out: String,
        val err: String,
        val timedOut: Boolean = false,
    ) {
        fun ok(): Boolean = code == 0 && !timedOut

        fun combinedText(): String {
            return buildString {
                append(out)
                append('\n')
                append(err)
            }
        }

        fun isRootDeniedOrMissing(): Boolean {
            val text = combinedText().lowercase(Locale.US)
            return text.contains("cannot run program \"su\"") ||
                text.contains("permission not granted") ||
                text.contains("permission denied") ||
                (text.contains("not found") && text.contains("su")) ||
                text.contains("inaccessible or not found") ||
                text.contains("root backend required")
        }

        fun isPrivilegeDeniedOrMissing(): Boolean = isRootDeniedOrMissing()
    }

    fun publishUserWarning(message: String) {
        AppWarningCenter.publish(message)
    }

    fun mode(): AccessMode = resolveMode(requireRoot = false, publishWarnings = false)

    fun ensureToolAvailable(tool: String): Boolean = ensureToolAvailable(tool, requireRoot = true)

    fun ensurePrivilegedToolAvailable(tool: String): Boolean = ensureToolAvailable(tool, requireRoot = false)

    private fun ensureToolAvailable(tool: String, requireRoot: Boolean): Boolean {
        val normalized = tool.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return false

        val mode = resolveMode(requireRoot = requireRoot, publishWarnings = true)
        if (mode == AccessMode.NONE) return false
        val cacheKey = "${if (requireRoot) "root" else "priv"}:$mode:$normalized"

        synchronized(this) {
            if (availableToolCache.contains(cacheKey)) return true
            if (missingToolCache.contains(cacheKey)) return false
        }

        val result = runWithMode(mode, listOf(buildToolProbeCommand(normalized)), logOnFailure = false, timeoutMs = TOOL_PROBE_TIMEOUT_MS)
        val available = result.ok()
        synchronized(this) {
            if (available) {
                availableToolCache += cacheKey
                missingToolCache -= cacheKey
            } else {
                missingToolCache += cacheKey
            }
        }
        if (!available) {
            publishUserWarning(ClusterApp.appContext.getString(R.string.msg_root_tool_missing_fmt, normalized))
        }
        return available
    }

    fun clearToolCache() {
        synchronized(this) {
            availableToolCache.clear()
            missingToolCache.clear()
            rootUnavailableCached = false
        }
    }

    @JvmOverloads
    fun exec(
        cmds: List<String>,
        logOnFailure: Boolean = true,
        timeoutMs: Long = RuntimeConfig.Root.SU_TIMEOUT_MS,
    ): Result {
        if (cmds.isEmpty()) return Result(code = 0, out = "", err = "")
        val mode = resolveMode(requireRoot = false, publishWarnings = true)
        return if (mode == AccessMode.NONE) {
            unavailableResult(requireRoot = false)
        } else {
            runWithMode(mode, cmds, logOnFailure = logOnFailure, timeoutMs = timeoutMs)
        }
    }

    @JvmOverloads
    fun su(
        cmds: List<String>,
        logOnFailure: Boolean = true,
        timeoutMs: Long = RuntimeConfig.Root.SU_TIMEOUT_MS,
    ): Result {
        if (cmds.isEmpty()) return Result(code = 0, out = "", err = "")
        val mode = resolveMode(requireRoot = true, publishWarnings = true)
        return if (mode == AccessMode.NONE) {
            unavailableResult(requireRoot = true)
        } else {
            runWithMode(mode, cmds, logOnFailure = logOnFailure, timeoutMs = timeoutMs)
        }
    }

    fun openInputStream(file: File): InputStream {
        val command = "cat ${shellQuote(file.absolutePath)}"
        return when (val mode = resolveMode(requireRoot = false, publishWarnings = true)) {
            AccessMode.SU -> openSuInputStream(command)
            AccessMode.NONE -> throw IllegalStateException(unavailableResult(requireRoot = false).err)
        }
    }

    private fun resolveMode(requireRoot: Boolean, publishWarnings: Boolean): AccessMode {
        if (rootUnavailableCached) {
            if (publishWarnings) publishUnavailableWarning(requireRoot)
            return AccessMode.NONE
        }

        val probe = runViaSu(listOf("exit 0"), logOnFailure = false, timeoutMs = TOOL_PROBE_TIMEOUT_MS)
        if (probe.ok()) {
            return AccessMode.SU
        }
        if (probe.isRootDeniedOrMissing()) {
            rootUnavailableCached = true
            if (publishWarnings) publishUnavailableWarning(requireRoot)
            return AccessMode.NONE
        }
        return AccessMode.SU
    }

    private fun runWithMode(mode: AccessMode, cmds: List<String>, logOnFailure: Boolean, timeoutMs: Long): Result {
        val result = when (mode) {
            AccessMode.SU -> runViaSu(cmds, logOnFailure = logOnFailure, timeoutMs = timeoutMs)
            AccessMode.NONE -> unavailableResult(requireRoot = false)
        }

        if (result.isRootDeniedOrMissing()) {
            publishUnavailableWarning(mode == AccessMode.SU)
        }
        return result
    }

    private fun runViaSu(cmds: List<String>, logOnFailure: Boolean, timeoutMs: Long): Result {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutReader = startReaderThread("su-stdout", process.inputStream, stdout)
            val stderrReader = startReaderThread("su-stderr", process.errorStream, stderr)

            DataOutputStream(process.outputStream).use { os ->
                for (command in cmds) {
                    os.write(command.toByteArray(Charsets.UTF_8))
                    os.writeByte('\n'.code)
                }
                os.writeBytes("exit\n")
                os.flush()
            }

            val result = awaitProcess(
                process = process,
                stdoutReader = stdoutReader,
                stderrReader = stderrReader,
                stdout = stdout,
                stderr = stderr,
                timeoutMs = timeoutMs,
                label = "su",
            )

            if ((!result.ok() || result.timedOut) && logOnFailure) {
                Log.w(TAG, "su завершился неуспешно: code=${result.code}, timedOut=${result.timedOut}\nSTDOUT:\n${result.out}\nSTDERR:\n${result.err}")
            }
            result
        } catch (t: Throwable) {
            val result = Result(code = -1, out = "", err = t.toString())
            if (result.isRootDeniedOrMissing()) {
                Log.w(TAG, "su недоступен для приложения: ${t.message}")
            } else {
                Log.e(TAG, "Ошибка выполнения su", t)
            }
            result
        } finally {
            try {
                process?.destroy()
            } catch (destroyError: Throwable) {
                Log.w(TAG, "Не удалось корректно завершить su-процесс", destroyError)
            }
        }
    }

    private fun awaitProcess(
        process: Process,
        stdoutReader: Thread,
        stderrReader: Thread,
        stdout: StringBuilder,
        stderr: StringBuilder,
        timeoutMs: Long,
        label: String,
    ): Result {
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(FORCE_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }

        joinReader(stdoutReader)
        joinReader(stderrReader)

        return if (finished) {
            Result(
                code = process.exitValue(),
                out = stdout.toString().trimEnd(),
                err = stderr.toString().trimEnd(),
            )
        } else {
            Result(
                code = EXIT_CODE_TIMEOUT,
                out = stdout.toString().trimEnd(),
                err = buildString {
                    append("Истек таймаут выполнения ")
                    append(label)
                    append("-команды (")
                    append(timeoutMs)
                    append(" мс)")
                    val stderrText = stderr.toString().trimEnd()
                    if (stderrText.isNotEmpty()) {
                        append('\n')
                        append(stderrText)
                    }
                },
                timedOut = true,
            )
        }
    }

    private fun unavailableResult(requireRoot: Boolean): Result {
        publishUnavailableWarning(requireRoot)
        return Result(
            code = -1,
            out = "",
            err = if (requireRoot) {
                "Root backend required: su"
            } else {
                "Privileged backend required: su"
            },
        )
    }

    private fun publishUnavailableWarning(requireRoot: Boolean) {
        val context = ClusterApp.appContext
        val message = if (requireRoot) {
            context.getString(R.string.msg_root_required)
        } else {
            context.getString(R.string.msg_privileged_access_required)
        }
        publishUserWarning(message)
    }

    private fun startReaderThread(name: String, inputStream: InputStream, sink: StringBuilder): Thread {
        return Thread({
            try {
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        sink.append(line).append('\n')
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Ошибка чтения потока $name", t)
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun joinReader(thread: Thread) {
        try {
            thread.join(READER_JOIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось дождаться завершения reader-потока ${thread.name}", t)
        }
    }

    private fun openSuInputStream(command: String): InputStream {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        return ProcessInputStream(process, process.inputStream)
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private class ProcessInputStream(
        private val process: Process,
        private val delegate: InputStream,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray): Int = delegate.read(b)

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            try {
                delegate.close()
            } finally {
                runCatching { process.destroy() }
            }
        }
    }

    private const val FORCE_DESTROY_GRACE_MS = 300L
    private const val READER_JOIN_TIMEOUT_MS = 500L
    private const val TOOL_PROBE_TIMEOUT_MS = 1_500L
    private const val EXIT_CODE_TIMEOUT = -2

    private fun buildToolProbeCommand(tool: String): String {
        return when (tool) {
            "ping" -> "if command -v ping >/dev/null 2>&1; then exit 0; elif [ -x /system/bin/ping ]; then exit 0; else exit 127; fi"
            else -> "command -v $tool >/dev/null 2>&1"
        }
    }
}
