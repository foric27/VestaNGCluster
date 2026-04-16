package ru.foric27.cluster

import android.util.Log

internal class UdpPipelineStartCoordinator(
    private val tag: String,
    private val createVideoEncoder: (StreamConfig, String?, UdpSender) -> VideoEncoder,
    private val closeSenderQuietly: (UdpSender) -> Unit,
    private val isCurrentSender: (UdpSender) -> Boolean,
    private val clearSenderIfCurrent: (UdpSender) -> Unit,
    private val assignEncoder: (VideoEncoder?) -> Unit,
    private val startStatusSync: (String?, String) -> Unit,
    private val stopTransportStats: () -> Unit,
    private val startTransportStats: () -> Unit,
    private val stopConnectivityWatchdog: () -> Unit,
    private val startConnectivityWatchdog: () -> Unit,
    private val setStreamActive: (Boolean) -> Unit,
    private val setStartInProgress: (Boolean) -> Unit,
    private val resetRestartBackoff: () -> Unit,
    private val setRestartBackoff: (Long) -> Unit,
    private val resetNoLinkWarning: () -> Unit,
    private val replayRootWarningIfPresent: () -> Unit,
    private val acquireStreamWakeLock: () -> Unit,
    private val releaseStreamWakeLock: () -> Unit,
    private val cancelPendingRestart: () -> Unit,
    private val cancelRecovery: () -> Unit,
    private val updateNotification: () -> Unit,
    private val scheduleRestart: (String, Throwable?) -> Unit,
    private val stopEncoderQuietly: () -> Unit,
) {

    fun startReadyPipeline(
        cfg: StreamConfig,
        hostValue: String,
        port: Int,
        bindIp: String?,
        launchComponent: String?,
        restartLog: Boolean,
        localSender: UdpSender,
        isVideoModeSelected: Boolean,
    ) {
        if (!isCurrentSender(localSender)) {
            closeSenderQuietly(localSender)
            setStartInProgress(false)
            return
        }

        if (!isVideoModeSelected) {
            startStatusOnly(bindIp, hostValue, localSender)
            return
        }

        startVideoMode(cfg, hostValue, port, bindIp, launchComponent, restartLog, localSender)
    }

    private fun startStatusOnly(bindIp: String?, hostValue: String, localSender: UdpSender) {
        try {
            closeSenderQuietly(localSender)
            clearSenderIfCurrent(localSender)
            stopTransportStats()
            stopConnectivityWatchdog()
            startStatusSync(bindIp, hostValue)
            setStreamActive(false)
            setStartInProgress(false)
            resetRestartBackoff()
            resetNoLinkWarning()
            releaseStreamWakeLock()
            cancelPendingRestart()
            cancelRecovery()
            updateNotification()
            Log.i(tag, "Активен режим бортового компьютера: видеотрансляция отключена")
        } catch (t: Throwable) {
            setStartInProgress(false)
            Log.e(tag, "Ошибка запуска status-only режима бортового компьютера", t)
            scheduleRestart("trip_mode_start", t)
        }
    }

    private fun startVideoMode(
        cfg: StreamConfig,
        hostValue: String,
        port: Int,
        bindIp: String?,
        launchComponent: String?,
        restartLog: Boolean,
        localSender: UdpSender,
    ) {
        try {
            val encoder = createVideoEncoder(cfg, launchComponent, localSender)
            assignEncoder(encoder)
            encoder.start()
            startStatusSync(bindIp, hostValue)
            startTransportStats()
            startConnectivityWatchdog()
            setStreamActive(true)
            setStartInProgress(false)
            setRestartBackoff(500L)
            resetNoLinkWarning()
            acquireStreamWakeLock()
            cancelPendingRestart()
            cancelRecovery()
            updateNotification()
            replayRootWarningIfPresent()
            if (restartLog) {
                Log.i(tag, "Перезапуск выполнен успешно")
            } else {
                Log.i(tag, "Стрим успешно запущен: $hostValue:$port")
            }
        } catch (t: Throwable) {
            setStartInProgress(false)
            setStreamActive(false)
            Log.e(tag, "Ошибка запуска кодера", t)
            closeSenderQuietly(localSender)
            clearSenderIfCurrent(localSender)
            stopEncoderQuietly()
            assignEncoder(null)
            scheduleRestart("enc_start", t)
        }
    }
}
