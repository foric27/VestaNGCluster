package ru.foric27.cluster

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal class UdpConnectivityWatchdogCoordinator(
    private val tag: String,
    private val launchWorker: (String, Int, () -> Unit) -> Thread,
    private val interruptThreadQuietly: (Thread?, String) -> Unit,
    private val joinThreadQuietly: (Thread?, String) -> Unit,
    private val configProvider: () -> StreamConfig?,
    private val senderProvider: () -> UdpSender?,
    private val hostProvider: () -> String?,
    private val activeRootIfaceProvider: () -> String?,
    private val ipFromCidr: (String) -> String?,
    private val logRouteVerdict: (String, RootNetUtil.RouteCheckResult) -> Unit,
    private val evaluatePeerReachability: (String, Boolean) -> PeerCheckResult,
    private val requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,
    private val routeRecentSendGraceMs: Long,
    private val connectivityWatchdogPeriodMs: Long,
    private val ifaceMissingRestartBackoffMinMs: Long,
    private val noRouteRestartBackoffMinMs: Long,
    private val routeFailuresBeforeRestart: Int,
    private val defaultUsbLocalCidr: String,
) {

    private var watchdogThread: Thread? = null
    private val watchdogStop = AtomicBoolean(false)
    @Volatile private var routeFailureStreak = 0

    fun start() {
        stop()
        watchdogStop.set(false)
        watchdogThread = launchWorker("ConnectivityWatchdog", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            while (!watchdogStop.get()) {
                try {
                    Thread.sleep(connectivityWatchdogPeriodMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (watchdogStop.get()) break

                val cfg = configProvider() ?: continue
                val currentSender = senderProvider() ?: continue
                val snapshot = currentSender.snapshot()
                val nowMs = SystemClock.elapsedRealtime()
                val targetHost = hostProvider()?.takeIf { it.isNotBlank() } ?: cfg.ip
                val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: defaultUsbLocalCidr
                val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: ipFromCidr(localCidr)
                val lastSendMs = snapshot.lastSendElapsedRealtimeMs
                val recentVideoTraffic = lastSendMs > 0L && (nowMs - lastSendMs) <= routeRecentSendGraceMs

                val activeProbeState = RootNetUtil.getIfaceProbeState(force = false)
                if (!activeProbeState.rootRequired && activeProbeState.exists && !activeProbeState.linkUp) {
                    Log.w(tag, "Watchdog: link ${activeProbeState.iface} down во время активного стрима")
                    requestImmediateRecovery(
                        "root_iface_link_down",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой линк ${activeProbeState.iface} потерян. Перезапускаю стрим…",
                    )
                    continue
                }
                if (!activeProbeState.rootRequired && !activeProbeState.exists) {
                    Log.w(tag, "Watchdog: ${activeProbeState.iface} пропал во время активного стрима")
                    requestImmediateRecovery(
                        RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                        ifaceMissingRestartBackoffMinMs,
                        "${activeProbeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                    )
                    continue
                }

                val pinnedIface = activeRootIfaceProvider()?.takeIf { it.isNotBlank() }
                val selectedIface = RootNetUtil.getSelectedIfaceName(force = false)
                if (!activeProbeState.rootRequired && !pinnedIface.isNullOrBlank() && !selectedIface.equals(pinnedIface, ignoreCase = true)) {
                    Log.w(tag, "Watchdog: активный root-интерфейс $pinnedIface сменился на ${selectedIface ?: "none"}")
                    requestImmediateRecovery(
                        "root_iface_changed",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой интерфейс $pinnedIface отключён или сменился. Перезапускаю стрим…",
                    )
                    continue
                }

                if (recentVideoTraffic) {
                    if (routeFailureStreak != 0) {
                        Log.i(tag, "Watchdog: есть свежая видеопередача, сбрасываю route-failure streak")
                    }
                    routeFailureStreak = 0
                    continue
                }

                val probeState = RootNetUtil.getIfaceProbeState(force = false)
                if (!probeState.rootRequired && probeState.exists && !probeState.linkUp) {
                    Log.w(tag, "Watchdog: link ${probeState.iface} down во время активного стрима")
                    requestImmediateRecovery(
                        "root_iface_link_down",
                        ifaceMissingRestartBackoffMinMs,
                        "Сетевой линк ${probeState.iface} потерян. Перезапускаю стрим…",
                    )
                    continue
                }
                if (!probeState.rootRequired && !probeState.exists) {
                    Log.w(tag, "Watchdog: ${probeState.iface} пропал во время активного стрима")
                    requestImmediateRecovery(
                        RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                        ifaceMissingRestartBackoffMinMs,
                        "${probeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                    )
                    continue
                }

                if (!expectedBindIp.isNullOrBlank()) {
                    val routeCheck = RootNetUtil.checkRouteTo(targetHost, expectedBindIp, forceProbe = false)
                    val routeReady = routeCheck.ok
                    logRouteVerdict("watchdog", routeCheck)
                    val peerCheck = if (routeReady) {
                        evaluatePeerReachability(targetHost, false)
                    } else {
                        PeerCheckResult(attempted = false, ok = false)
                    }
                    val probeOk = if (!peerCheck.attempted) {
                        try {
                            currentSender.probe()
                        } catch (t: Throwable) {
                            Log.w(tag, "Watchdog: исключение при UDP probe во время route-check", t)
                            false
                        }
                    } else {
                        false
                    }
                    val connectionHealthy = ConnectivityHealth.isWatchdogConnectionHealthy(
                        recentVideoTraffic = recentVideoTraffic,
                        routeReady = routeReady,
                        peerCheck = peerCheck,
                        udpProbeOk = probeOk,
                    )
                    if (connectionHealthy) {
                        if (routeFailureStreak != 0) {
                            Log.i(tag, "Watchdog: связность подтверждена, сбрасываю route-failure streak")
                        }
                        routeFailureStreak = 0
                    } else {
                        routeFailureStreak += 1
                        val failureReason = when {
                            !routeReady -> "маршрут недоступен"
                            peerCheck.failed -> "ping до $targetHost не проходит"
                            else -> "нет живой UDP-отправки"
                        }
                        Log.w(tag, "Watchdog: $failureReason через ${probeState.iface} (streak=$routeFailureStreak)")
                        if (routeFailureStreak >= routeFailuresBeforeRestart) {
                            routeFailureStreak = 0
                            requestImmediateRecovery(
                                if (peerCheck.failed) "peer_unreachable_runtime" else "route_lost_runtime",
                                noRouteRestartBackoffMinMs,
                                if (peerCheck.failed) {
                                    "Приёмник $targetHost недоступен по ping. Перезапуск стрима…"
                                } else {
                                    "Маршрут через ${probeState.iface} потерян. Перезапуск стрима…"
                                },
                            )
                            continue
                        }
                    }
                }
                // Для режима Dynamic FPS отсутствие новых видеокадров само по себе не считается ошибкой:
                // при статичной картинке на VirtualDisplay кодек может долго не отдавать выходные буферы.
                // Восстановление здесь выполняется только по проверке маршрута, probe и явным ошибкам сокета или энкодера.
            }
        }
    }

    fun stop() {
        watchdogStop.set(true)
        interruptThreadQuietly(watchdogThread, "watchdog")
        joinThreadQuietly(watchdogThread, "watchdog")
        watchdogThread = null
    }

    fun resetRouteFailureStreak() {
        routeFailureStreak = 0
    }

    fun currentRouteFailureStreak(): Int = routeFailureStreak
}
