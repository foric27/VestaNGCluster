package ru.foric27.cluster

import android.os.SystemClock
import android.util.Log

internal class UdpStartupProbeCoordinator(
    private val tag: String,
    private val routeWaitStepMs: Long,
    private val closeSenderQuietly: (UdpSender) -> Unit,
    private val clearSenderIfCurrent: (UdpSender) -> Unit,
    private val setStartInProgress: (Boolean) -> Unit,
    private val increaseRestartBackoff: (Long) -> Long,
    private val logPipelineSnapshot: (String) -> Unit,
    private val notifyNoRoute: (String, Long) -> Unit,
    private val scheduleRestart: (String, Throwable?) -> Unit,
) {

    fun waitForUdpReady(
        sender: UdpSender,
        dstHost: String,
        timeoutMs: Long,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var lastLoggedWaitMs = 0L

        while (true) {
            if (sender.isClosed()) return false

            val probeOk = try {
                sender.probe()
            } catch (t: Throwable) {
                Log.w(tag, "Исключение при проверке UDP", t)
                false
            }

            if (probeOk) {
                return true
            }

            val waitedMs = SystemClock.elapsedRealtime() - startedAt
            if (waitedMs >= timeoutMs) {
                return false
            }

            if (waitedMs - lastLoggedWaitMs >= 1_000L) {
                lastLoggedWaitMs = waitedMs
                Log.i(tag, "Ожидание готовности UDP: dst=$dstHost probeOk=$probeOk waited=${waitedMs}ms")
            }

            try {
                Thread.sleep(routeWaitStepMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    fun handleRouteNotReady(
        localSender: UdpSender,
        hostValue: String,
        noRouteRestartBackoffMinMs: Long,
    ) {
        closeSenderQuietly(localSender)
        clearSenderIfCurrent(localSender)
        setStartInProgress(false)
        val backoffMs = increaseRestartBackoff(noRouteRestartBackoffMinMs)
        logPipelineSnapshot("Маршрут не готов для $hostValue")
        Log.w(tag, "Маршрут до $hostValue не готов; повторю позже. backoff=${backoffMs}ms")
        notifyNoRoute(hostValue, backoffMs)
        scheduleRestart("net_wait", null)
    }
}
