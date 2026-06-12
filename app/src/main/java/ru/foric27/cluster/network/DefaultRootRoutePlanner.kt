package ru.foric27.cluster.network

/** Продакшен-адаптер чистого [RootNetworkRoutePlanner] под интерфейсный seam. */
internal object DefaultRootRoutePlanner : RootRoutePlanner {
    /**
     * Делегирует планирование маршрута в [RootNetworkRoutePlanner].
     *
     * @param iface имя сетевого интерфейса
     * @param localCidr локальная подсеть в формате CIDR
     * @param gatewayIp IP шлюза
     * @param routingTable имя или номер routing table
     * @param includeFwmarkRule включить fwmark-правило
     * @return Result с планом маршрутизации
     */
    override fun plan(
        iface: String,
        localCidr: String,
        gatewayIp: String,
        routingTable: String,
        includeFwmarkRule: Boolean,
    ): Result<RootNetworkRoutePlan> {
        return RootNetworkRoutePlanner.plan(
            iface = iface,
            localCidr = localCidr,
            gatewayIp = gatewayIp,
            routingTable = routingTable,
            includeFwmarkRule = includeFwmarkRule,
        )
    }
}
