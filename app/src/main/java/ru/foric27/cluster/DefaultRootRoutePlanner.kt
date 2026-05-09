package ru.foric27.cluster

/** Продакшен-адаптер чистого [RootNetworkRoutePlanner] под интерфейсный seam. */
internal object DefaultRootRoutePlanner : RootRoutePlanner {
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
