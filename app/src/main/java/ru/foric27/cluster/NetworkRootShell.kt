package ru.foric27.cluster

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
internal class NetworkRootShell {

    fun execScript(commands: List<String>): Result<String> {
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
                    Timber.tag(TAG).w(t, "Ошибка network shell, пересоздаю persistent shell")
                }
            }
            return Result.failure(lastFailure ?: IllegalStateException("Не удалось выполнить network script"))
        }
    }

    fun isAvailable(): Boolean {
        synchronized(lock) {
            if (closed) return false
            return try {
                val shell = getOrCreateShellLocked(forceRecreate = false)
                shell.isRoot && shell.isAlive
            } catch (_: Throwable) {
                discardShellLocked()
                false
            }
        }
    }

    fun close() {
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

    private companion object {
        private const val TAG = "NetworkRootShell"
        private const val MAX_ATTEMPTS = 2
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
    }

    private val lock = Any()

    @Volatile
    private var shell: Shell? = null

    @Volatile
    private var closed = false
}
