package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.config.*
import ru.foric27.cluster.network.*
import ru.foric27.cluster.service.*
import ru.foric27.cluster.util.*

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Координатор watchdog-мониторинга связности стрима.
 *
 * В фоновой корутине проверяет link/interface/route/encoder.
 * При превышении порога ошибок маршрута запрашивает immediate recovery.
 * Отслеживает grace-период после смены маршрута.
 */
internal class UdpConnectivityWatchdogCoordinator(
    private val tag: String,
    private val scope: CoroutineScope,
    private val configProvider: () -> StreamConfig?,
    private val senderProvider: () -> UdpSender?,
    private val hostProvider: () -> String?,
    private val activeRootIfaceProvider: () -> String?,
    private val ipFromCidr: (String) -> String?,
    private val logRouteVerdict: (String, RootNetUtil.RouteCheckResult) -> Unit,
    private val requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,

    private val routeRecentSendGraceMs: Long,
    private val connectivityWatchdogPeriodMs: Long,
    private val ifaceMissingRestartBackoffMinMs: Long,
    private val noRouteRestartBackoffMinMs: Long,
    private val routeFailuresBeforeRestart: Int,
    private val defaultUsbLocalCidr: String,
) {

    private var watchdogJob: Job? = null
    @Volatile private var routeFailureStreak = 0
    @Volatile private var ifaceMissingGraceLogged = false

    /**
     * Запускает фоновую корутину watchdog.
     *
     * Останавливает предыдущую корутину перед запуском новой.
     */
    fun start() {
        stop()
        ifaceMissingGraceLogged = false
        watchdogJob = CoroutineWorker.launchPeriodicWorker(
            scope = scope,
            name = "ConnectivityWatchdog",
            intervalMs = connectivityWatchdogPeriodMs,
            threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
        ) {
            try {
                val cfg = configProvider() ?: return@launchPeriodicWorker
                val currentSender = senderProvider() ?: return@launchPeriodicWorker
                val snapshot = currentSender.snapshot()
                val nowMs = SystemClock.elapsedRealtime()
                val targetHost = hostProvider()?.takeIf { it.isNotBlank() } ?: cfg.ip
                val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: defaultUsbLocalCidr
                val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: ipFromCidr(localCidr)
                val lastSendSuccessMs = snapshot.lastSendSuccessElapsedRealtimeMs
                val recentVideoTraffic = lastSendSuccessMs > 0L && (nowMs - lastSendSuccessMs) <= routeRecentSendGraceMs

                val activeProbeState = RootNetUtil.getIfaceProbeState(force = false)
                if (!activeProbeState.rootRequired && activeProbeState.exists && !activeProbeState.linkUp) {
                    Timber.tag(tag).w("Watchdog: link ${activeProbeState.iface} down во время активного стрима")
                    requestImmediateRecovery(
                        "root_iface_link_down",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой линк ${activeProbeState.iface} потерян. Перезапускаю стрим…",
                    )
                    return@launchPeriodicWorker
                }
                if (!activeProbeState.exists && !RootNetUtil.wasSelectedIfaceEverPresent) {
                    if (!ifaceMissingGraceLogged) {
                        Timber.tag(tag).w("Watchdog: ${activeProbeState.iface} отсутствует; восстановление не требуется — интерфейс никогда не поднимался")
                        ifaceMissingGraceLogged = true
                    }
                    return@launchPeriodicWorker
                }
                if (!activeProbeState.rootRequired && !activeProbeState.exists) {
                    Timber.tag(tag).w("Watchdog: ${activeProbeState.iface} пропал во время активного стрима")
                    requestImmediateRecovery(
                        RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                        ifaceMissingRestartBackoffMinMs,
                        "${activeProbeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                    )
                    return@launchPeriodicWorker
                }

                val pinnedIface = activeRootIfaceProvider()?.takeIf { it.isNotBlank() }
                val selectedIface = RootNetUtil.getSelectedIfaceName(force = false)
                if (!activeProbeState.rootRequired && !pinnedIface.isNullOrBlank() && !selectedIface.equals(pinnedIface, ignoreCase = true)) {
                    Timber.tag(tag).w("Watchdog: активный root-интерфейс $pinnedIface сменился на ${selectedIface ?: "none"}")
                    requestImmediateRecovery(
                        "root_iface_changed",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой интерфейс $pinnedIface отключён или сменился. Перезапускаю стрим…",
                    )
                    return@launchPeriodicWorker
                }

                if (recentVideoTraffic) {
                    if (routeFailureStreak != 0) {
                        Timber.tag(tag).i("Watchdog: есть свежая видеопередача, сбрасываю route-failure streak")
                    }
                    routeFailureStreak = 0
                    return@launchPeriodicWorker
                }

                val probeState = activeProbeState
                if (!probeState.rootRequired && probeState.exists && !probeState.linkUp) {
                    Timber.tag(tag).w("Watchdog: link ${probeState.iface} down во время активного стрима")
                    requestImmediateRecovery(
                        "root_iface_link_down",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой линк ${probeState.iface} потерян. Перезапускаю стрим…",
                    )
                    return@launchPeriodicWorker
                }
                if (!probeState.exists && !RootNetUtil.wasSelectedIfaceEverPresent) {
                    if (!ifaceMissingGraceLogged) {
                        Timber.tag(tag).i("Watchdog: ${probeState.iface} отсутствует; восстановление не требуется — интерфейс никогда не поднимался")
                        ifaceMissingGraceLogged = true
                    }
                    return@launchPeriodicWorker
                }
                if (!probeState.rootRequired && !probeState.exists) {
                    Timber.tag(tag).w("Watchdog: ${probeState.iface} пропал во время активного стрима")
                    requestImmediateRecovery(
                        RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                        ifaceMissingRestartBackoffMinMs,
                        "${probeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                    )
                    return@launchPeriodicWorker
                }

                if (!expectedBindIp.isNullOrBlank()) {
                    val routeCheck = RootNetUtil.checkRouteTo(targetHost, expectedBindIp, forceProbe = false)
                    val routeReady = routeCheck.ok
                    logRouteVerdict("watchdog", routeCheck)
                    val probeOk = if (routeReady) {
                        try {
                            currentSender.probe()
                        } catch (t: Throwable) {
                            Timber.tag(tag).w(t, "Watchdog: исключение при UDP probe во время route-check")
                            false
                        }
                    } else {
                        false
                    }

                    val connectionHealthy = ConnectivityHealth.isWatchdogConnectionHealthy(
                        recentVideoTraffic = recentVideoTraffic,
                        routeReady = routeReady,
                        udpProbeOk = probeOk,
                    )

                    if (connectionHealthy) {
                        if (routeFailureStreak != 0) {
                            Timber.tag(tag).i("Watchdog: связность подтверждена, сбрасываю route-failure streak")
                        }
                        routeFailureStreak = 0
                    } else {
                        routeFailureStreak += 1
                        val failureReason = when {
                            !routeReady -> "маршрут недоступен"
                            else -> "нет живой UDP-отправки"
                        }

                        Timber.tag(tag).w("Watchdog: $failureReason через ${probeState.iface} (streak=$routeFailureStreak)")
                        if (routeFailureStreak >= routeFailuresBeforeRestart) {
                            routeFailureStreak = 0
                            requestImmediateRecovery(
                                "route_lost_runtime",
                                noRouteRestartBackoffMinMs,
                                "Маршрут через ${probeState.iface} потерян. Перезапуск стрима…",
                            )

                            return@launchPeriodicWorker
                        }
                    }
                }
                // Для режима Dynamic FPS отсутствие новых видеокадров само по себе не считается ошибкой:
                // при статичной картинке на VirtualDisplay кодек может долго не отдавать выходные буферы.
                // Восстановление здесь выполняется только по проверке маршрута, probe и явным ошибкам сокета или энкодера.
            } catch (t: Throwable) {
                Timber.tag(tag).e(t, "Watchdog: непредвиденная ошибка в цикле")
            }
        }
    }

    /**
     * Останавливает фоновую корутину watchdog.
     *
     * Отменяет job и ожидает завершения.
     */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Сбрасывает счётчик последовательных ошибок маршрута.
     *
     * Вызывается при остановке стрима или успешном восстановлении.
     */
    fun resetRouteFailureStreak() {
        routeFailureStreak = 0
    }

    /**
     * Возвращает текущее количество последовательных ошибок маршрута.
     *
     * @return текущий streak ошибок
     */
    fun currentRouteFailureStreak(): Int = routeFailureStreak
}
