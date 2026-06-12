package ru.foric27.cluster.network

/**
 * Ручной seam для persistent root-shell сетевых мутаций.
 *
 * Продакшен-композиция использует [NetworkRootShell], а тесты могут передать fake,
 * который возвращает нужные ответы на `ip`/`iptables` без доступа к устройству.
 */
internal interface NetworkRootCommandExecutor : AutoCloseable {
    /**
     * Выполняет список network-команд через persistent root shell.
     *
     * @param commands список однострочных shell-команд
     * @return Result с stdout при успехе или исключение при ошибке
     */
    fun execScript(commands: List<String>): Result<String>

    /**
     * Проверяет доступность root shell.
     *
     * @return true если shell доступен и активен
     */
    fun isAvailable(): Boolean

    /**
     * Закрывает persistent shell и освобождает ресурсы.
     */
    override fun close()
}
