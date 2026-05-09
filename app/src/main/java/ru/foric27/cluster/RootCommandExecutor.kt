package ru.foric27.cluster

/**
 * Узкий seam для one-shot root-команд, чтобы запуск activity через root можно было
 * проверять без libsu и реального `su`.
 */
internal fun interface RootCommandExecutor {
    fun run(
        cmds: List<String>,
        logOnFailure: Boolean,
        timeoutMs: Long,
    ): RootCommandRunner.Result
}
