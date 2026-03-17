package ru.foric27.cluster

import android.os.Process
import android.os.SystemClock
import android.util.Log

internal class UdpConnectivityWatchdog(
    private val launchWorker: (name: String, threadPriority: Int, block: () -> Unit) -> Thread,
    private val interruptThreadQuietly: (Thread?, String) -> Unit,
    private val joinThreadQuietly: (Thread?, String) -> Unit,
    private val stateProvider: () -> UdpConnectivityWatchdogState?,
    private val requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,
) {

    private var watchdogThread: Thread? = null
    @Volatile private var stop = false

    fun start() {
        stop()
        stop = false
        watchdogThread = launchWorker("ConnectivityWatchdog", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            while (!stop) {
                try {
                    Thread.sleep(CONNECTIVITY_WATCHDOG_PERIOD_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (stop) break
                val state = stateProvider() ?: continue
                state.evaluateAndMaybeRecover(requestImmediateRecovery)
            }
        }
    }

    fun stop() {
        stop = true
        interruptThreadQuietly(watchdogThread, "watchdog")
        joinThreadQuietly(watchdogThread, "watchdog")
        watchdogThread = null
    }

    private companion object {
        private const val CONNECTIVITY_WATCHDOG_PERIOD_MS = 2_000L
    }
}

internal data class UdpConnectivityWatchdogState(
    val sender: UdpSender,
    val cfg: StreamConfig,
    val host: String,
    val activeRootIface: String?,
    val routeFailureStreak: Int,
    val routeFailureThreshold: Int,
    val routeRecentSendGraceMs: Long,
    val missingBackoffMs: Long,
    val noRouteBackoffMs: Long,
    val onRouteFailureStreakChanged: (Int) -> Unit,
) {
    fun evaluateAndMaybeRecover(
        requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,
    ): Boolean {
        val snapshot = sender.snapshot()
        val nowMs = SystemClock.elapsedRealtime()
        val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: DEF_USB_LOCAL_CIDR
        val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: ipFromCidr(localCidr)
        val lastSendMs = snapshot.lastSendElapsedRealtimeMs
        val recentVideoTraffic = lastSendMs > 0L && (nowMs - lastSendMs) <= routeRecentSendGraceMs

        if (cfg.useRootNet) {
            val activeProbeState = RootNetUtil.getIfaceProbeState(force = false)
            if (!activeProbeState.rootRequired && !activeProbeState.exists) {
                Log.w(TAG, "Watchdog: ${activeProbeState.iface} пропал во время активного стрима")
                requestImmediateRecovery(
                    RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                    missingBackoffMs,
                    "${activeProbeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                )
                return true
            }

            val pinnedIface = activeRootIface?.takeIf { it.isNotBlank() }
            val selectedIface = RootNetUtil.getSelectedIfaceName(force = false)
            if (
                !activeProbeState.rootRequired &&
                !pinnedIface.isNullOrBlank() &&
                !selectedIface.equals(pinnedIface, ignoreCase = true)
            ) {
                Log.w(TAG, "Watchdog: активный root-интерфейс $pinnedIface сменился на ${selectedIface ?: "none"}")
                requestImmediateRecovery(
                    "root_iface_changed",
                    missingBackoffMs,
                    "Сетевой интерфейс $pinnedIface отключён или сменился. Перезапускаю стрим…",
                )
                return true
            }

            if (recentVideoTraffic) {
                onRouteFailureStreakChanged(0)
                return false
            }

            val probeState = RootNetUtil.getIfaceProbeState(force = false)
            if (!probeState.rootRequired && !probeState.exists) {
                Log.w(TAG, "Watchdog: ${probeState.iface} пропал во время активного стрима")
                requestImmediateRecovery(
                    RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                    missingBackoffMs,
                    "${probeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                )
                return true
            }

            if (!expectedBindIp.isNullOrBlank()) {
                val routeReady = RootNetUtil.canRouteTo(host, expectedBindIp, forceProbe = false)
                if (routeReady) {
                    onRouteFailureStreakChanged(0)
                } else {
                    val probeOk = try {
                        sender.probe()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Watchdog: исключение при UDP probe во время route-check", t)
                        false
                    }
                    if (probeOk) {
                        onRouteFailureStreakChanged(0)
                    } else {
                        val nextStreak = routeFailureStreak + 1
                        onRouteFailureStreakChanged(nextStreak)
                        Log.w(TAG, "Watchdog: маршрут до $host через ${probeState.iface} недоступен и нет живой передачи (streak=$nextStreak)")
                        if (nextStreak >= routeFailureThreshold) {
                            onRouteFailureStreakChanged(0)
                            requestImmediateRecovery(
                                "route_lost_runtime",
                                noRouteBackoffMs,
                                "Маршрут через ${probeState.iface} потерян. Перезапуск стрима…",
                            )
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun ipFromCidr(cidr: String): String? {
        return try {
            cidr.substringBefore('/').trim().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        private const val TAG = "UdpConnectivityWatchdog"
        private const val DEF_USB_LOCAL_CIDR = "192.168.40.1/24"
    }
}
