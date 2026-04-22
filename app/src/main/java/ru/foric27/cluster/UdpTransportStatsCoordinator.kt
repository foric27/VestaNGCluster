package ru.foric27.cluster

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class UdpTransportStatsCoordinator(
    private val tag: String,
    private val senderSnapshotProvider: () -> UdpSender.StatsSnapshot?,
    private val statusSnapshotProvider: () -> UdpStatusSyncCoordinator.StatsSnapshot,
    private val launchWorker: (String, Int, () -> Unit) -> Thread,
    private val interruptThreadQuietly: (Thread?, String) -> Unit,
    private val joinThreadQuietly: (Thread?, String) -> Unit,
) {

    @Volatile private var statsThread: Thread? = null
    private val statsStop = AtomicBoolean(false)

    fun start() {
        stop()
        statsStop.set(false)
        statsThread = launchWorker("UdpStats", Process.THREAD_PRIORITY_DEFAULT) {
            var prevVideoFrames = 0L
            var prevVideoPackets = 0L
            var prevVideoBytes = 0L
            var prevProbePackets = 0L
            var prevProbeBytes = 0L
            var prevStatusPackets = 0L
            var prevStatusBytes = 0L
            var prevErrors = 0L

            while (!statsStop.get()) {
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (statsStop.get()) break

                val snapshot = senderSnapshotProvider() ?: continue
                val statusSnapshot = statusSnapshotProvider()
                val statusPackets = statusSnapshot.packetsSent
                val statusBytes = statusSnapshot.bytesSent
                val totalErrors = snapshot.sendErrors + statusSnapshot.sendErrors

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
                val lastSendAgo = if (snapshot.lastSendElapsedRealtimeMs > 0L) {
                    SystemClock.elapsedRealtime() - snapshot.lastSendElapsedRealtimeMs
                } else {
                    -1L
                }

                Log.i(
                    tag,
                    String.format(
                        Locale.US,
                        "UDP stats | dst=%s:%d | video: +%d frames, +%d packets, +%d bytes, %.3f Mbps | probes: +%d packets, +%d bytes | status: +%d packets, +%d bytes, %.2f kbps | errors:+%d | lastSendAgo=%dms",
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
                        lastSendAgo,
                    ),
                )
            }
        }
    }

    fun stop() {
        statsStop.set(true)
        interruptThreadQuietly(statsThread, "статистики")
        joinThreadQuietly(statsThread, "статистики")
        statsThread = null
    }
}
