package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UdpPipelineStartCoordinatorTest {

    @Test
    fun `encoder startup exception raises minimum backoff before enc_start restart`() {
        val sender = UdpSender("127.0.0.1", 5004, null)
        val startupError = VideoEncoderStartupException(
            reason = VideoEncoderStartupFailureReason.CONFIGURE,
            message = "configure failed",
            cause = IllegalStateException("boom"),
        )
        var requestedMinBackoff: Long? = null
        var scheduledReason: String? = null
        var scheduledCause: Throwable? = null

        try {
            val coordinator = UdpPipelineStartCoordinator(
                tag = "test",
                createVideoEncoder = { _, _, _ -> throw startupError },
                closeSenderQuietly = { it.close() },
                isCurrentSender = { true },
                clearSenderIfCurrent = {},
                assignEncoder = {},
                startStatusSync = { _, _ -> },
                stopTransportStats = {},
                startTransportStats = {},
                stopConnectivityWatchdog = {},
                startConnectivityWatchdog = {},
                setStreamActive = {},
                setStartInProgress = {},
                resetRestartBackoff = {},
                setRestartBackoff = {},
                ensureMinRestartBackoff = { minBackoffMs ->
                    requestedMinBackoff = minBackoffMs
                    minBackoffMs
                },
                resetNoLinkWarning = {},
                replayRootWarningIfPresent = {},
                acquireStreamWakeLock = {},
                releaseStreamWakeLock = {},
                cancelPendingRestart = {},
                cancelRecovery = {},
                updateNotification = {},
                scheduleRestart = { reason, cause ->
                    scheduledReason = reason
                    scheduledCause = cause
                },
                stopEncoderQuietly = {},
            )

            coordinator.startReadyPipeline(
                cfg = sampleConfig(),
                hostValue = "192.168.40.2",
                port = 5004,
                bindIp = "192.168.40.1",
                launchComponent = null,
                restartLog = false,
                localSender = sender,
                isVideoModeSelected = true,
            )

            assertEquals(RuntimeConfig.Service.CODEC_ERROR_RESTART_DEBOUNCE_MS, requestedMinBackoff)
            assertEquals("enc_start", scheduledReason)
            assertSame(startupError, scheduledCause)
        } finally {
            sender.close()
        }
    }

    private fun sampleConfig(): StreamConfig {
        return StreamConfig(
            ip = "192.168.40.2",
            port = 5004,
            launchComponent = null,
            localCidr = "192.168.40.1/24",
            gateway = "192.168.40.2",
            bindIp = "192.168.40.1",
            width = 1280,
            height = 720,
            dpi = 160,
            fps = 30,
            bitrate = 8_000_000,
            iframeIntervalSec = 1,
        )
    }
}
