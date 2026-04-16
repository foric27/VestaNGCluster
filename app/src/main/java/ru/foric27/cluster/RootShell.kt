package ru.foric27.cluster

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Выполнение shell-команд через `su`.
 *
 * Обёртка держит единый формат результата, ограничивает время выполнения
 * и читает stdout/stderr параллельно, чтобы не зависать на заполненных буферах процесса.
 */
object RootShell {

    private const val TAG = "RootShell"
    const val ROOT_REQUIRED_WARNING = "Нужно дать приложению права root (su)"
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
                text.contains("permission denied") ||
                (text.contains("not found") && text.contains("su")) ||
                text.contains("inaccessible or not found")
        }
    }

    fun publishUserWarning(message: String) {
        AppWarningCenter.publish(message)
    }

    fun ensureToolAvailable(tool: String): Boolean {
        val normalized = tool.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return false

        synchronized(this) {
            if (availableToolCache.contains(normalized)) return true
            if (missingToolCache.contains(normalized)) return false
        }

        val result = su(listOf(buildToolProbeCommand(normalized)), logOnFailure = false)
        val available = result.ok()
        synchronized(this) {
            if (available) {
                availableToolCache += normalized
                missingToolCache -= normalized
            } else {
                missingToolCache += normalized
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
        }
    }

    private fun publishRootWarningIfNeeded(result: Result) {
        if (result.isRootDeniedOrMissing()) {
            rootUnavailableCached = true
            AppWarningCenter.publish(ROOT_REQUIRED_WARNING)
        }
    }

    @JvmOverloads
    fun su(
        cmds: List<String>,
        logOnFailure: Boolean = true,
        timeoutMs: Long = RuntimeConfig.Root.SU_TIMEOUT_MS,
    ): Result {
        if (cmds.isEmpty()) {
            return Result(code = 0, out = "", err = "")
        }

        if (rootUnavailableCached) {
            return Result(
                code = -1,
                out = "",
                err = "Cannot run program \"su\": permission denied or not found",
            ).also(::publishRootWarningIfNeeded)
        }

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

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(FORCE_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }

            joinReader(stdoutReader)
            joinReader(stderrReader)

            val result = if (finished) {
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
                        append("Истек таймаут выполнения su-команды (")
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

            publishRootWarningIfNeeded(result)
            if ((!result.ok() || result.timedOut) && logOnFailure) {
                Log.w(
                    TAG,
                    "su завершился неуспешно: code=${result.code}, timedOut=${result.timedOut}\n" +
                        "STDOUT:\n${result.out}\nSTDERR:\n${result.err}",
                )
            }
            result
        } catch (t: Throwable) {
            val result = Result(code = -1, out = "", err = t.toString())
            if (result.isRootDeniedOrMissing()) {
                Log.w(TAG, "su недоступен для приложения: ${t.message}")
            } else {
                Log.e(TAG, "Ошибка выполнения su", t)
            }
            result.also(::publishRootWarningIfNeeded)
        } finally {
            try {
                process?.destroy()
            } catch (destroyError: Throwable) {
                Log.w(TAG, "Не удалось корректно завершить su-процесс", destroyError)
            }
        }
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
        }, name).also { it.start() }
    }

    private fun joinReader(thread: Thread) {
        try {
            thread.join(READER_JOIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось дождаться завершения reader-потока ${thread.name}", t)
        }
    }

    private const val FORCE_DESTROY_GRACE_MS = 300L
    private const val READER_JOIN_TIMEOUT_MS = 500L
    private const val EXIT_CODE_TIMEOUT = -2

    private fun buildToolProbeCommand(tool: String): String {
        return when (tool) {
            "ping" -> "if command -v ping >/dev/null 2>&1; then exit 0; elif [ -x /system/bin/ping ]; then exit 0; else exit 127; fi"
            else -> "command -v $tool >/dev/null 2>&1"
        }
    }
}
