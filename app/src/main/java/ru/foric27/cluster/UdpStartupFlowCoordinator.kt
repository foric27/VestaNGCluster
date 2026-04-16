package ru.foric27.cluster

import android.util.Log

internal class UdpStartupFlowCoordinator(
    private val tag: String,
    private val createSender: (String, Int, String?) -> UdpSender,
    private val assignSender: (UdpSender?) -> Unit,
    private val setStartInProgress: (Boolean) -> Unit,
    private val scheduleRestart: (String, Throwable?) -> Unit,
    private val launchUdpProbe: (() -> Unit) -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
    private val waitForUdpReady: (UdpSender, String, Long) -> Boolean,
    private val handleRouteNotReady: (UdpSender, String, Long) -> Unit,
) {

    fun start(
        hostValue: String,
        port: Int,
        bindIp: String?,
        routeWaitTimeoutMs: Long,
        noRouteRestartBackoffMinMs: Long,
        onReadyPipeline: (UdpSender) -> Unit,
    ) {
        val localSender = try {
            createSender(hostValue, port, bindIp)
        } catch (t: Throwable) {
            setStartInProgress(false)
            Log.e(tag, "Не удалось создать UdpSender", t)
            scheduleRestart("udp_sender_init", t)
            return
        }

        assignSender(localSender)
        setStartInProgress(true)

        launchUdpProbe {
            val udpReady = waitForUdpReady(localSender, hostValue, routeWaitTimeoutMs)
            if (!udpReady) {
                postToMain {
                    handleRouteNotReady(localSender, hostValue, noRouteRestartBackoffMinMs)
                }
                return@launchUdpProbe
            }

            postToMain {
                onReadyPipeline(localSender)
            }
        }
    }
}
