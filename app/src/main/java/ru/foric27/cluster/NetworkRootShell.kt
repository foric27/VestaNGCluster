package ru.foric27.cluster

import android.os.SystemClock
import timber.log.Timber
import com.topjohnwu.superuser.Shell
import java.io.IOException
import java.util.Locale

/**
 * Узкий persistent-root wrapper только для сетевых shell-мутаций.
 *
 * Все команды сериализуются через один lock, чтобы не смешивать изменения
 * policy-routing/iptables между разными потоками сервиса.
 */
internal class NetworkRootShell : NetworkRootCommandExecutor {

    override fun execScript(commands: List<String>): Result<String> {
        val normalizedCommands = try {
            normalizeCommands(commands)
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        synchronized(lock) {
            if (closed) {
                return Result.failure(IllegalStateException("NetworkRootShell уже закрыт"))
            }

            var lastFailure: Throwable? = null
            repeat(MAX_ATTEMPTS) { attemptIndex ->
                val recreateShell = attemptIndex > 0
                try {
                    val shell = getOrCreateShellLocked(forceRecreate = recreateShell)

                    if (!shell.isAlive) {
                        val failure = IOException("libsu shell неактивен перед выполнением script")
                        lastFailure = failure
                        discardShellLocked()
                        if (attemptIndex + 1 < MAX_ATTEMPTS) {
                            Timber.tag(TAG).w("libsu shell неактивен перед script, пересоздаю и повторяю попытку")
                            return@repeat
                        }
                        return Result.failure(failure)
                    }

                    val result = shell
                        .newJob()
                        .add(*normalizedCommands.toTypedArray())
                        .to(mutableListOf(), mutableListOf())
                        .exec()

                    val stdout = result.out.joinToString(separator = "\n").trimEnd()
                    val stderr = result.err.joinToString(separator = "\n").trimEnd()
                    if (result.isSuccess) {
                        return Result.success(stdout)
                    }

                    val failure = buildExecutionFailure(result.code, stdout, stderr)
                    if (shouldRetry(shell, result.code, failure) && attemptIndex + 1 < MAX_ATTEMPTS) {
                        lastFailure = failure
                        discardShellLocked()
                        Timber.tag(TAG).w("libsu shell умер во время network script, пересоздаю и повторяю попытку")
                        return@repeat
                    }
                    return Result.failure(failure)
                } catch (t: Throwable) {
                    lastFailure = t
                    discardShellLocked()
                    if (attemptIndex + 1 >= MAX_ATTEMPTS) {
                        return Result.failure(t)
                    }
                    // Exponential backoff: 100ms → 250ms → 500ms
                    val backoffMs = (RETRY_BASE_DELAY_MS * (attemptIndex + 1)).coerceAtMost(RETRY_MAX_DELAY_MS)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    Timber.tag(TAG).w(t, "Ошибка network shell (попытка ${attemptIndex + 1}/$MAX_ATTEMPTS), жду ${backoffMs}мс и пересоздаю persistent shell")
                }
            }
            return Result.failure(lastFailure ?: IllegalStateException("Не удалось выполнить network script"))
        }
    }

    override fun isAvailable(): Boolean {
        synchronized(lock) {
            if (closed) return false
            return try {
                val shell = getOrCreateShellLocked(forceRecreate = false)
                val available = shell.isRoot && shell.isAlive
                if (!available) {
                    logAvailabilityFailure(
                        message = "Root-доступ недоступен: shell.isRoot=${shell.isRoot}, shell.isAlive=${shell.isAlive}",
                    )
                } else {
                    resetAvailabilityFailureLogState()
                }
                available
            } catch (t: Throwable) {
                logAvailabilityFailure(
                    message = "Root-доступ недоступен: исключение при проверке shell",
                    error = t,
                )
                discardShellLocked()
                false
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            discardShellLocked()
        }
    }

    private fun getOrCreateShellLocked(forceRecreate: Boolean): Shell {
        if (forceRecreate) {
            discardShellLocked()
        }
        val existing = shell
        if (existing != null && existing.isAlive) {
            return existing
        }
        discardShellLocked()
        return Shell.getShell().also { created ->
            if (!created.isRoot) {
                discardShellLocked(created)
                throw IOException("libsu shell не получил root-доступ")
            }
            if (!created.isAlive) {
                discardShellLocked(created)
                throw IOException("libsu shell создан, но уже неактивен")
            }
            shell = created
        }
    }

    private fun discardShellLocked(candidate: Shell? = shell) {
        if (candidate === shell) {
            shell = null
        }
        runCatching { candidate?.close() }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось закрыть libsu shell") }
    }

    private fun normalizeCommands(commands: List<String>): List<String> {
        require(commands.isNotEmpty()) { "Список network-команд пуст" }
        return commands.mapIndexed { index, rawCommand ->
            val command = rawCommand.trim()
            require(command.isNotEmpty()) { "Пустая network-команда: #${index + 1}" }
            require('\n' !in command && '\r' !in command && '\u0000' !in command) {
                "Network-команда должна быть однострочной: #${index + 1}"
            }
            validateNoCommandInjection(command = command, index = index)
            require(isAllowedNetworkCommand(command)) {
                "Разрешены только сетевые root-команды: #${index + 1}"
            }
            command
        }
    }

    internal fun validateNoCommandInjection(command: String, index: Int = 0) {
        val blockedToken = BLOCKED_SHELL_TOKENS.firstOrNull { token -> command.contains(token) }
        require(blockedToken == null) {
            "Опасные shell-конструкции запрещены для network-команд: #${index + 1} (token=$blockedToken)"
        }
    }

    private fun isAllowedNetworkCommand(command: String): Boolean {
        val lower = command.lowercase(Locale.US)
        return ALLOWED_PREFIXES.any { lower == it || lower.startsWith("$it ") }
    }

    private fun shouldRetry(shell: Shell, exitCode: Int, failure: Throwable): Boolean {
        return !shell.isAlive || exitCode == Shell.Result.JOB_NOT_EXECUTED || failure is IOException
    }

    private fun buildExecutionFailure(exitCode: Int, stdout: String, stderr: String): IOException {
        val message = buildString {
            append("Network root script завершился с кодом ")
            append(exitCode)
            if (stdout.isNotEmpty()) {
                append("\n[STDOUT]\n")
                append(stdout)
            }
            if (stderr.isNotEmpty()) {
                append("\n[STDERR]\n")
                append(stderr)
            }
        }
        return IOException(message)
    }

    private fun logAvailabilityFailure(message: String, error: Throwable? = null) {
        val nowMs = SystemClock.elapsedRealtime()
        val signature = buildString {
            append(message)
            if (error != null) {
                append('|')
                append(error.javaClass.name)
                append(':')
                append(error.message)
            }
        }
        val sameFailure = signature == lastAvailabilityFailureSignature
        val withinWindow = sameFailure && (nowMs - lastAvailabilityFailureLogElapsedRealtimeMs) < AVAILABILITY_FAILURE_LOG_WINDOW_MS
        if (withinWindow) {
            suppressedAvailabilityFailureCount += 1
            return
        }

        val suppressedCount = suppressedAvailabilityFailureCount
        val repeatedFailure = sameFailure && suppressedCount > 0
        lastAvailabilityFailureSignature = signature
        lastAvailabilityFailureLogElapsedRealtimeMs = nowMs
        suppressedAvailabilityFailureCount = 0

        if (repeatedFailure) {
            Timber.tag(TAG).w("$message; повторов suppressed=$suppressedCount")
            return
        }

        if (error == null) {
            Timber.tag(TAG).w(message)
        } else {
            Timber.tag(TAG).w(error, message)
        }
    }

    private fun resetAvailabilityFailureLogState() {
        lastAvailabilityFailureSignature = null
        lastAvailabilityFailureLogElapsedRealtimeMs = 0L
        suppressedAvailabilityFailureCount = 0
    }

    private companion object {
        private const val TAG = "NetworkRootShell"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 100L
        private const val RETRY_MAX_DELAY_MS = 500L
        private const val AVAILABILITY_FAILURE_LOG_WINDOW_MS = 5_000L
        private val BLOCKED_SHELL_TOKENS = listOf(
            "$(",
            "&&",
            "||",
            "|",
            ">",
            "<",
            "&",
            ";",
            "`",
            "#",
        )
        private val ALLOWED_PREFIXES = setOf(
            "ip",
            "iptables",
            "ifconfig",
            "route",
            "tc",
            "ndc",
            "cmd network",
        )

        @Volatile
        private var lastAvailabilityFailureSignature: String? = null

        @Volatile
        private var lastAvailabilityFailureLogElapsedRealtimeMs: Long = 0L

        @Volatile
        private var suppressedAvailabilityFailureCount: Int = 0
    }

    private val lock = Any()

    @Volatile
    private var shell: Shell? = null

    @Volatile
    private var closed = false
}
