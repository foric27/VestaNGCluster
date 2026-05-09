package ru.foric27.cluster

/**
 * Seam для построения policy-route команд до исполнения через root-shell.
 */
internal fun interface RootRoutePlanner {
    fun plan(
        iface: String,
        localCidr: String,
        gatewayIp: String,
        routingTable: String,
        includeFwmarkRule: Boolean,
    ): Result<RootNetworkRoutePlan>
}
