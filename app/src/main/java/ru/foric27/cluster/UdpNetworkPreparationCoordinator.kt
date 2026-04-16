package ru.foric27.cluster

import android.util.Log

internal data class UdpNetworkPreparationResult(
    val bindIp: String?,
    val ifacePresent: Boolean,
    val rootRequired: Boolean,
    val ifaceName: String?,
)

internal class UdpNetworkPreparationCoordinator(
    private val tag: String,
    private val defaultUsbLocalCidr: String,
    private val defaultUsbGateway: String,
    private val ipFromCidr: (String) -> String?,
    private val logRouteVerdict: (String, RootNetUtil.RouteCheckResult) -> Unit,
) {

    fun prepare(cfg: StreamConfig): UdpNetworkPreparationResult {
        RootNetUtil.logSelectedIface(tag, "Подготовка сети")
        val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: defaultUsbLocalCidr
        val gateway = cfg.gateway?.takeIf { it.isNotBlank() } ?: defaultUsbGateway
        val applyResult = RootNetUtil.applyStaticIfaceNetwork(localCidr, gateway)
        if (applyResult.rootRequired) {
            Log.w(tag, "Нет доступа к su/root — сетевой root-режим недоступен")
            return UdpNetworkPreparationResult(null, true, true, applyResult.iface)
        }

        val defaultBindIp = ipFromCidr(localCidr)
        var bindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: defaultBindIp
        if (!applyResult.ok && (cfg.bindIp.isNullOrBlank() || cfg.bindIp == defaultBindIp)) {
            bindIp = null
        }

        val probeState = RootNetUtil.getIfaceProbeState()
        if (probeState.rootRequired) {
            Log.w(tag, "Нет доступа к su/root — проверка ${probeState.iface} недоступна")
            return UdpNetworkPreparationResult(null, true, true, probeState.iface)
        }

        val ifacePresent = probeState.exists
        if (!applyResult.ok) {
            if (ifacePresent) {
                Log.w(tag, "Не удалось применить статический IP для ${applyResult.iface} (продолжаю): ${applyResult.details}")
            } else {
                Log.i(tag, "${probeState.iface} отсутствует — статическая настройка сети пропущена")
            }
        }

        if (!ifacePresent) {
            Log.i(tag, "${probeState.iface} отсутствует на устройстве — запуск root-сети отложен")
        } else if (!bindIp.isNullOrBlank()) {
            logRouteVerdict("startup", RootNetUtil.checkRouteTo(cfg.ip, bindIp, forceProbe = true))
        }

        return UdpNetworkPreparationResult(bindIp, ifacePresent, false, probeState.iface)
    }
}
