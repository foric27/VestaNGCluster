package ru.foric27.cluster.network
import ru.foric27.cluster.R
import ru.foric27.cluster.*
import ru.foric27.cluster.config.*
import ru.foric27.cluster.util.*

import timber.log.Timber
import com.topjohnwu.superuser.Shell
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Generic libsu runner for privileged one-shot commands outside network setup.
 *
 * Network mutations must keep using [NetworkRootShell], which validates and
 * serializes policy-routing/iptables commands separately.
 */
internal object RootCommandRunner : RootCommandExecutor {

    private const val TAG = "RootCommandRunner"

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
                text.contains("root backend required") ||
                text.contains("no root") ||
                text.contains("not root")
        }
    }

    private val runnerLock = Any()
    @Volatile private var shellAlive: Boolean = true

    override fun run(
        cmds: List<String>,
        logOnFailure: Boolean,
        timeoutMs: Long,
    ): Result {
        if (cmds.isEmpty()) return Result(code = 0, out = "", err = "")

        synchronized(runnerLock) {
            if (!shellAlive) {
                Timber.tag(TAG).i("libsu shell был закрыт после прошлого таймаута, пересоздаю")
                runCatching { Shell.getShell()?.close() }
                shellAlive = true
            }

            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "libsu-root-command").apply { isDaemon = true }
            }
            val future = executor.submit<Result> { runViaLibSu(cmds) }

            return try {
                val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                if (!result.ok() && logOnFailure) {
                    Timber.tag(TAG).w("libsu command failed: code=${result.code}, timedOut=${result.timedOut}\nSTDOUT:\n${result.out}\nSTDERR:\n${result.err}")
                }
                if (result.isRootDeniedOrMissing()) {
                    publishRootRequiredWarning()
                }
                result
            } catch (_: TimeoutException) {
                future.cancel(true)
                runCatching { Shell.getShell()?.close() }
                shellAlive = false
                val result = Result(
                    code = EXIT_CODE_TIMEOUT,
                    out = "",
                    err = "Истек таймаут выполнения libsu-команды ($timeoutMs мс)",
                    timedOut = true,
                )
                if (logOnFailure) {
                    Timber.tag(TAG).w("libsu command timed out after ${timeoutMs}ms — закрываю мёртвый shell")
                }
                result
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                val result = Result(code = -1, out = "", err = cause.toString())
                if (result.isRootDeniedOrMissing()) {
                    publishRootRequiredWarning()
                    Timber.tag(TAG).w("libsu root недоступен для приложения: ${cause.message}")
                } else {
                    Timber.tag(TAG).e(cause, "Ошибка выполнения libsu-команды")
                }
                result
            } finally {
                executor.shutdownNow()
            }
        }
    }

    fun run(cmds: List<String>): Result {
        return run(cmds = cmds, logOnFailure = true, timeoutMs = RuntimeConfig.Root.SU_TIMEOUT_MS)
    }

    fun run(cmds: List<String>, logOnFailure: Boolean): Result {
        return run(cmds = cmds, logOnFailure = logOnFailure, timeoutMs = RuntimeConfig.Root.SU_TIMEOUT_MS)
    }

    private fun runViaLibSu(cmds: List<String>): Result {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val result = Shell
            .cmd(*cmds.toTypedArray())
            .to(stdout, stderr)
            .exec()
        return Result(
            code = result.code,
            out = stdout.joinToString(separator = "\n").trimEnd(),
            err = stderr.joinToString(separator = "\n").trimEnd(),
        )
    }

    private fun publishRootRequiredWarning() {
        AppWarningCenter.publish(ClusterApp.appContext.getString(R.string.msg_root_required))
    }

    private const val EXIT_CODE_TIMEOUT = -2
}
