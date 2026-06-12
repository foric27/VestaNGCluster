package ru.foric27.cluster.network

/**
 * Ручной seam для persistent root-shell сетевых мутаций.
 *
 * Продакшен-композиция использует [NetworkRootShell], а тесты могут передать fake,
 * который возвращает нужные ответы на `ip`/`iptables` без доступа к устройству.
 */
internal interface NetworkRootCommandExecutor : AutoCloseable {
    fun execScript(commands: List<String>): Result<String>

    fun isAvailable(): Boolean

    override fun close()
}
