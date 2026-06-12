package ru.foric27.cluster.network

/**
 * Узкий seam для one-shot root-команд, чтобы запуск activity через root можно было
 * проверять без libsu и реального `su`.
 */
internal fun interface RootCommandExecutor {
    /**
     * Выполняет список root-команд.
     *
     * @param cmds список shell-команд
     * @param logOnFailure логировать ли ошибку
     * @param timeoutMs таймаут в миллисекундах
     * @return результат выполнения
     */
    fun run(
        cmds: List<String>,
        logOnFailure: Boolean,
        timeoutMs: Long,
    ): RootCommandRunner.Result
}
