package ru.foric27.cluster

import timber.log.Timber

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
            Timber.tag(tag).w("Нет privileged backend с root-правами — сетевой root-режим недоступен")
            return UdpNetworkPreparationResult(null, true, true, applyResult.iface)
        }

        val defaultBindIp = ipFromCidr(localCidr)
        var bindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: defaultBindIp
        if (!applyResult.ok && (cfg.bindIp.isNullOrBlank() || cfg.bindIp == defaultBindIp)) {
            bindIp = null
        }

        val probeState = RootNetUtil.getIfaceProbeState()
        if (probeState.rootRequired) {
            Timber.tag(tag).w("Нет privileged backend с root-правами — проверка ${probeState.iface} недоступна")
            return UdpNetworkPreparationResult(null, true, true, probeState.iface)
        }

        val ifacePresent = probeState.exists
        if (!applyResult.ok) {
            if (ifacePresent) {
                Timber.tag(tag).w("Не удалось применить статический IP для ${applyResult.iface} (продолжаю): ${applyResult.details}")
            } else {
                Timber.tag(tag).i("${probeState.iface} отсутствует — статическая настройка сети пропущена")
            }
        }

        if (!ifacePresent) {
            Timber.tag(tag).i("${probeState.iface} отсутствует на устройстве — запуск root-сети отложен")
        } else if (!bindIp.isNullOrBlank()) {
            logRouteVerdict("startup", RootNetUtil.checkRouteTo(cfg.ip, bindIp, forceProbe = true))
        }

        return UdpNetworkPreparationResult(bindIp, ifacePresent, false, probeState.iface)
    }
}
