package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.R
import ru.foric27.cluster.service.*
import ru.foric27.cluster.util.CoroutineWorker

import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import timber.log.Timber
import java.util.Locale

/**
 * Координатор сбора и логирования UDP-статистики.
 *
 * Периодически (каждые ~5 секунд) запрашивает снимки от [UdpSender] и
 * [UdpStatusSyncCoordinator], считает дельту по кадрам/пакетам/байтам
 * и логирует Mbps, ошибки и probe-статистику.
 */
internal class UdpTransportStatsCoordinator(
    private val context: Context,
    private val tag: String,
    private val scope: CoroutineScope,
    private val senderSnapshotProvider: () -> UdpSender.StatsSnapshot?,
    private val statusSnapshotProvider: () -> UdpStatusSyncCoordinator.StatsSnapshot,
) {

    private var statsJob: Job? = null

    /**
     * Запускает корутину сбора статистики.
     *
     * Останавливает предыдущую корутину перед запуском новой.
     */
    fun start() {
        stop()
        statsJob = CoroutineWorker.launchPeriodicWorker(
            scope = scope,
            name = "UdpStats",
            intervalMs = 5_000L,
            threadPriority = Process.THREAD_PRIORITY_DEFAULT,
        ) {
            var prevVideoFrames = 0L
            var prevVideoPackets = 0L
            var prevVideoBytes = 0L
            var prevProbePackets = 0L
            var prevProbeBytes = 0L
            var prevStatusPackets = 0L
            var prevStatusBytes = 0L
            var prevErrors = 0L

            val snapshot = senderSnapshotProvider() ?: return@launchPeriodicWorker
            val statusSnapshot = statusSnapshotProvider()
            val statusPackets = statusSnapshot.packetsSent
            val statusBytes = statusSnapshot.bytesSent
            val totalErrors = snapshot.sendErrors + statusSnapshot.sendErrors

            val countersReset = snapshot.videoFramesSent < prevVideoFrames ||
                snapshot.videoPacketsSent < prevVideoPackets ||
                snapshot.videoBytesSent < prevVideoBytes ||
                snapshot.probePacketsSent < prevProbePackets ||
                snapshot.probeBytesSent < prevProbeBytes ||
                statusPackets < prevStatusPackets ||
                statusBytes < prevStatusBytes ||
                totalErrors < prevErrors

            if (countersReset) {
                prevVideoFrames = snapshot.videoFramesSent
                prevVideoPackets = snapshot.videoPacketsSent
                prevVideoBytes = snapshot.videoBytesSent
                prevProbePackets = snapshot.probePacketsSent
                prevProbeBytes = snapshot.probeBytesSent
                prevStatusPackets = statusPackets
                prevStatusBytes = statusBytes
                prevErrors = totalErrors
                Timber.tag(tag).i(
                    context.getString(R.string.udp_stats_baseline_reset),
                )
                return@launchPeriodicWorker
            }

            val deltaFrames = snapshot.videoFramesSent - prevVideoFrames
            val deltaPackets = snapshot.videoPacketsSent - prevVideoPackets
            val deltaBytes = snapshot.videoBytesSent - prevVideoBytes
            val deltaProbePackets = snapshot.probePacketsSent - prevProbePackets
            val deltaProbeBytes = snapshot.probeBytesSent - prevProbeBytes
            val deltaStatusPackets = statusPackets - prevStatusPackets
            val deltaStatusBytes = statusBytes - prevStatusBytes
            val deltaErrors = totalErrors - prevErrors

            prevVideoFrames = snapshot.videoFramesSent
            prevVideoPackets = snapshot.videoPacketsSent
            prevVideoBytes = snapshot.videoBytesSent
            prevProbePackets = snapshot.probePacketsSent
            prevProbeBytes = snapshot.probeBytesSent
            prevStatusPackets = statusPackets
            prevStatusBytes = statusBytes
            prevErrors = totalErrors

            val videoMbps = deltaBytes * 8.0 / 2_000_000.0
            val statusKbps = deltaStatusBytes * 8.0 / 2_000.0
            val lastSendAttemptAgo = if (snapshot.lastSendAttemptElapsedRealtimeMs > 0L) {
                SystemClock.elapsedRealtime() - snapshot.lastSendAttemptElapsedRealtimeMs
            } else {
                -1L
            }
            val lastSendSuccessAgo = if (snapshot.lastSendSuccessElapsedRealtimeMs > 0L) {
                SystemClock.elapsedRealtime() - snapshot.lastSendSuccessElapsedRealtimeMs
            } else {
                -1L
            }

            Timber.tag(tag).i(String.format(
                    Locale.US,
                    "UDP stats | dst=%s:%d | video: +%d frames, +%d packets, +%d bytes, %.3f Mbps | probes: +%d packets, +%d bytes | status: +%d packets, +%d bytes, %.2f kbps | errors:+%d | consecutiveSendErrors=%d | lastSendAttemptAgo=%dms | lastSendSuccessAgo=%dms",
                    snapshot.host,
                    snapshot.port,
                    deltaFrames,
                    deltaPackets,
                    deltaBytes,
                    videoMbps,
                    deltaProbePackets,
                    deltaProbeBytes,
                    deltaStatusPackets,
                    deltaStatusBytes,
                    statusKbps,
                    deltaErrors,
                    snapshot.consecutiveFrameSendErrors,
                    lastSendAttemptAgo,
                    lastSendSuccessAgo,
                ),
            )
        }
    }

    /**
     * Останавливает корутину сбора статистики.
     *
     * Отменяет job.
     */
    fun stop() {
        statsJob?.cancel()
        statsJob = null
    }
}
