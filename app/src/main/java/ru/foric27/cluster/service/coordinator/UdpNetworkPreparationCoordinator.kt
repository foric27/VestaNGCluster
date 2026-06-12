package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.network.*
import ru.foric27.cluster.service.*

import timber.log.Timber

/**
 * Результат подготовки сетевого маршрута для streaming.
 *
 * Sealed class с 6 вариантами результата, отражающими все возможные
 * состояния сетевой подготовки: успех, отсутствие интерфейса,
 * недоступность root, таймаут маршрута, невозможность применения
 * маршрута, недоступность сети.
 *
 * @property bindIp IP-адрес для привязки UDP sender, или null при ошибке
 * @property ifaceName имя сетевого интерфейса, или null при ошибке
 */
internal sealed class RoutePreparationResult {
    abstract val bindIp: String?
    abstract val ifaceName: String?

    /** Успешная подготовка: маршрут настроен, интерфейс доступен. */
    data class Success(
        override val bindIp: String?,
        override val ifaceName: String?,
    ) : RoutePreparationResult()

    /** Запрошенный интерфейс отсутствует на устройстве. */
    data class IfaceMissing(
        override val ifaceName: String?,
        val details: String,
    ) : RoutePreparationResult() {
        override val bindIp: String? = null
    }

    /** Root-доступ недоступен — сетевые операции невозможны. */
    data class RootUnavailable(
        override val ifaceName: String?,
        val details: String,
    ) : RoutePreparationResult() {
        override val bindIp: String? = null
    }

    /** Таймаут при ожидании применения маршрута. */
    data class RouteTimeout(
        override val ifaceName: String?,
        val details: String,
    ) : RoutePreparationResult() {
        override val bindIp: String? = null
    }

    /** Маршрут не удалось применить (например, конфликт IP). */
    data class RouteNotApplied(
        override val ifaceName: String?,
        override val bindIp: String?,
        val details: String,
    ) : RoutePreparationResult()

    /** Сеть недоступна (нет ping до gateway, нет link). */
    data class NetworkUnreachable(
        override val ifaceName: String?,
        override val bindIp: String?,
        val details: String,
    ) : RoutePreparationResult()

    val statusName: String
        get() = when (this) {
            is Success -> "Success"
            is IfaceMissing -> "IfaceMissing"
            is RootUnavailable -> "RootUnavailable"
            is RouteTimeout -> "RouteTimeout"
            is RouteNotApplied -> "RouteNotApplied"
            is NetworkUnreachable -> "NetworkUnreachable"
        }
}

/**
 * Обёртка над [RoutePreparationResult] с удобными boolean-аксессорами.
 *
 * @property routeReady `true` только при [RoutePreparationResult.Success]
 * @property ifacePresent `false` только при [RoutePreparationResult.IfaceMissing]
 * @property rootRequired `true` при [RoutePreparationResult.RootUnavailable]
 */
internal data class UdpNetworkPreparationResult(
    val routePreparation: RoutePreparationResult,
) {
    val bindIp: String? get() = routePreparation.bindIp
    val ifaceName: String? get() = routePreparation.ifaceName
    val ifacePresent: Boolean get() = routePreparation !is RoutePreparationResult.IfaceMissing
    val rootRequired: Boolean get() = routePreparation is RoutePreparationResult.RootUnavailable
    val routeReady: Boolean get() = routePreparation is RoutePreparationResult.Success
}

/**
 * Координатор подготовки сети для UDP streaming.
 *
 * Выполняет root-операции для настройки сетевого интерфейса:
 * 1. Применение статического IP (CIDR) и gateway через [RootNetUtil.applyStaticIfaceNetwork]
 * 2. Проверка наличия интерфейса через [RootNetUtil.getIfaceProbeState]
 * 3. Проверка маршрута до целевого IP через [RootNetUtil.checkRouteTo]
 * 4. Привязка host route к выбранному интерфейсу через [RootNetUtil.applyHostRoute]
 *
 * @param tag префикс для логов Timber
 * @param defaultUsbLocalCidr fallback CIDR когда не задан в [StreamConfig]
 * @param defaultUsbGateway fallback gateway когда не задан в [StreamConfig]
 * @param ipFromCidr функция извлечения IP из CIDR-строки
 * @param logRouteVerdict callback для логирования результата проверки маршрута
 */
internal class UdpNetworkPreparationCoordinator(
    private val tag: String,
    private val defaultUsbLocalCidr: String,
    private val defaultUsbGateway: String,
    private val ipFromCidr: (String) -> String?,
    private val logRouteVerdict: (String, RootNetUtil.RouteCheckResult) -> Unit,
) {

    /**
     * Подготавливает сеть для streaming по заданной конфигурации.
     *
     * Порядок операций:
     * 1. Применяет статический IP из [cfg.localCidr] или [defaultUsbLocalCidr]
     * 2. Проверяет наличие root и интерфейса
     * 3. Проверяет маршрут до [cfg.ip]
     * 4. Привязывает host route к интерфейсу (pinning)
     *
     * @param cfg конфигурация потока с сетевыми параметрами
     * @return результат подготовки с boolean-флагами для быстрой проверки
     */
    fun prepare(cfg: StreamConfig): UdpNetworkPreparationResult {
        RootNetUtil.logSelectedIface(tag, "Подготовка сети")
        val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: defaultUsbLocalCidr
        val gateway = cfg.gateway?.takeIf { it.isNotBlank() } ?: defaultUsbGateway
        val applyResult = RootNetUtil.applyStaticIfaceNetwork(localCidr, gateway)
        if (applyResult.rootRequired) {
            Timber.tag(tag).w("Нет privileged backend с root-правами — сетевой root-режим недоступен")
            return emitResult(RoutePreparationResult.RootUnavailable(applyResult.iface, applyResult.details))
        }

        val defaultBindIp = ipFromCidr(localCidr)
        var bindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: defaultBindIp
        if (!applyResult.ok && (cfg.bindIp.isNullOrBlank() || cfg.bindIp == defaultBindIp)) {
            bindIp = null
        }

        val probeState = RootNetUtil.getIfaceProbeState()
        if (probeState.rootRequired) {
            Timber.tag(tag).w("Нет privileged backend с root-правами — проверка ${probeState.iface} недоступна")
            return emitResult(RoutePreparationResult.RootUnavailable(probeState.iface, probeState.details))
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
            return emitResult(RoutePreparationResult.IfaceMissing(probeState.iface, probeState.details))
        } else if (!bindIp.isNullOrBlank()) {
            val routeCheck = RootNetUtil.checkRouteTo(cfg.ip, bindIp, forceProbe = true)
            logRouteVerdict("startup", routeCheck)
            if (!routeCheck.ok) {
                return emitResult(classifyRouteFailure(routeCheck, bindIp))
            }
        }

        if (!applyResult.ok) {
            return emitResult(classifyApplyFailure(applyResult, bindIp))
        }

        // Принудительно привязываем маршрут до целевого IP к выбранному интерфейсу
        // Это предотвращает уход трафика на другие интерфейсы (например, LTE)
        val targetIp = cfg.ip?.trim()?.takeIf { it.isNotBlank() }
        if (!targetIp.isNullOrBlank() && !bindIp.isNullOrBlank() && probeState.iface.isNotBlank()) {
            RootNetUtil.applyHostRoute(targetIp, probeState.iface, bindIp)
        }

        return emitResult(RoutePreparationResult.Success(bindIp, probeState.iface))
    }

    private fun classifyApplyFailure(
        applyResult: RootNetUtil.ApplyResult,
        bindIp: String?,
    ): RoutePreparationResult {
        return when (applyResult.failureReason) {
            RootNetUtil.FailureReason.ROOT_UNAVAILABLE -> RoutePreparationResult.RootUnavailable(applyResult.iface, applyResult.details)
            RootNetUtil.FailureReason.IFACE_MISSING -> RoutePreparationResult.IfaceMissing(applyResult.iface, applyResult.details)
            RootNetUtil.FailureReason.ROUTE_TIMEOUT -> RoutePreparationResult.RouteTimeout(applyResult.iface, applyResult.details)
            RootNetUtil.FailureReason.NETWORK_UNREACHABLE -> RoutePreparationResult.NetworkUnreachable(applyResult.iface, bindIp, applyResult.details)
            RootNetUtil.FailureReason.ROUTE_NOT_APPLIED, null -> RoutePreparationResult.RouteNotApplied(applyResult.iface, bindIp, applyResult.details)
        }
    }

    private fun classifyRouteFailure(
        routeCheck: RootNetUtil.RouteCheckResult,
        bindIp: String?,
    ): RoutePreparationResult {
        return when (routeCheck.failureReason()) {
            RootNetUtil.FailureReason.ROOT_UNAVAILABLE -> RoutePreparationResult.RootUnavailable(routeCheck.iface, routeCheck.output)
            RootNetUtil.FailureReason.ROUTE_TIMEOUT -> RoutePreparationResult.RouteTimeout(routeCheck.iface, routeCheck.output)
            RootNetUtil.FailureReason.ROUTE_NOT_APPLIED -> RoutePreparationResult.RouteNotApplied(routeCheck.iface, bindIp, routeCheck.output)
            RootNetUtil.FailureReason.IFACE_MISSING -> RoutePreparationResult.IfaceMissing(routeCheck.iface, routeCheck.output)
            RootNetUtil.FailureReason.NETWORK_UNREACHABLE -> RoutePreparationResult.NetworkUnreachable(routeCheck.iface, bindIp, routeCheck.output)
            null -> RoutePreparationResult.RouteNotApplied(routeCheck.iface, bindIp, routeCheck.output)
        }
    }

    private fun emitResult(result: RoutePreparationResult): UdpNetworkPreparationResult {
        Timber.tag(tag).i(
            "Route preparation result: status=${result.statusName}, iface=${result.ifaceName ?: "none"}, bindIp=${result.bindIp ?: "none"}",
        )
        return UdpNetworkPreparationResult(result)
    }
}
