package ru.foric27.cluster.network

/**
 * Seam для построения policy-route команд до исполнения через root-shell.
 *
 * Позволяет тестам подменять планировщик без реального доступа к сети.
 */
internal fun interface RootRoutePlanner {
    /**
     * Строит план маршрутизации для заданного интерфейса и подсети.
     *
     * @param iface имя сетевого интерфейса
     * @param localCidr локальная подсеть в формате CIDR
     * @param gatewayIp IP шлюза
     * @param routingTable имя или номер routing table
     * @param includeFwmarkRule включить fwmark-правило
     * @return Result с планом маршрутизации
     */
    fun plan(
        iface: String,
        localCidr: String,
        gatewayIp: String,
        routingTable: String,
        includeFwmarkRule: Boolean,
    ): Result<RootNetworkRoutePlan>
}
