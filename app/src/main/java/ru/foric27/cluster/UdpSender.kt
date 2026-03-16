package ru.foric27.cluster

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Отправка видеоданных по UDP.
 *
 * Крупные буферы режутся на фрагменты размера, совместимого с текущей конфигурацией sender.
 * Размер одного UDP-пакета задаётся через StreamConfig и в этой версии возвращён
 * к OEM-значению из оригинального приложения.
 */
class UdpSender(
    private val host: String,
    private val port: Int,
    bindIp: String?,
    private val bindNetwork: Network? = null,
    private val maxPayloadBytes: Int = RuntimeConfig.Network.UDP_MAX_PAYLOAD_BYTES,
    private val pacingMaxBps: Int = RuntimeConfig.Network.UDP_PACING_MAX_BPS,
) {

    private val bindIp: String? = bindIp?.trim()?.takeIf { it.isNotEmpty() }

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    @Volatile private var closed: Boolean = false

    private val safePayloadBytes: Int = maxPayloadBytes.coerceIn(MIN_UDP_PAYLOAD_BYTES, MAX_UDP_PAYLOAD_BYTES_LIMIT)
    private val safePacingMaxBps: Int = pacingMaxBps.coerceAtLeast(MIN_PACING_BPS)

    private val videoFramesSent = AtomicLong(0)
    private val videoPacketsSent = AtomicLong(0)
    private val videoBytesSent = AtomicLong(0)
    private val probePacketsSent = AtomicLong(0)
    private val probeBytesSent = AtomicLong(0)
    private val sendErrors = AtomicLong(0)
    @Volatile private var lastSendElapsedRealtimeMs: Long = 0L

    private var pacingDebtBytes: Int = 0
    private var pacingNextSendNs: Long = 0L

    init {
        createSocket()
    }

    @Throws(IOException::class)
    private fun createSocket() {
        closed = false
        address = InetAddress.getByName(host)

        socket = if (bindIp != null) {
            try {
                DatagramSocket(InetSocketAddress(InetAddress.getByName(bindIp), 0)).also {
                    Log.i(TAG, "Сокет привязан к локальному IP $bindIp")
                }
            } catch (be: BindException) {
                Log.w(TAG, "Не удалось привязаться к $bindIp (${be.message}), использую bind 0.0.0.0")
                DatagramSocket()
            }
        } else {
            DatagramSocket()
        }

        socket?.broadcast = false
        try {
            socket?.sendBufferSize = 1 shl 20
        } catch (_: Throwable) {
        }

        if (bindNetwork != null) {
            try {
                bindNetwork.bindSocket(socket)
                Log.i(TAG, "Сокет привязан к Network=$bindNetwork")
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось привязать сокет к Network (продолжаю без привязки): $t")
            }
        }

        resetPacing()
        Log.i(TAG, "Сокет создан для $host:$port, payload=$safePayloadBytes, pacingMax=${safePacingMaxBps}бит/с")
    }

    @Synchronized
    @Throws(IOException::class)
    fun sendFrame(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        val s = socket
        val a = address
        if (s == null || s.isClosed || closed) {
            throw SocketException("UDP-сокет закрыт")
        }
        if (a == null) {
            throw SocketException("UDP: адрес назначения не задан")
        }

        videoFramesSent.incrementAndGet()

        var offset = 0
        try {
            while (offset < data.size) {
                val len = minOf(safePayloadBytes, data.size - offset)
                val packet = DatagramPacket(data, offset, len, a, port)
                s.send(packet)
                videoPacketsSent.incrementAndGet()
                videoBytesSent.addAndGet(len.toLong())
                lastSendElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                offset += len
                paceAfterPacket(len)
            }
        } catch (e: IOException) {
            sendErrors.incrementAndGet()
            throw e
        } catch (e: RuntimeException) {
            sendErrors.incrementAndGet()
            throw e
        }
    }

    /**
     * Проверка, что сокет действительно может выполнить отправку.
     * Вызывать только из фонового потока.
     */
    @Synchronized
    fun probe(): Boolean {
        val s = socket
        val a = address
        if (s == null || s.isClosed || closed || a == null) return false
        return try {
            val payload = byteArrayOf(0)
            val packet = DatagramPacket(payload, 0, payload.size, a, port)
            s.send(packet)
            probePacketsSent.incrementAndGet()
            probeBytesSent.addAndGet(payload.size.toLong())
            lastSendElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            sendErrors.incrementAndGet()
            Log.w(TAG, "Проверочная отправка UDP не удалась", t)
            false
        }
    }

    @Synchronized
    fun restart() {
        try {
            socket?.close()
            createSocket()
            Log.i(TAG, "Сокет перезапущен")
        } catch (e: IOException) {
            Log.e(TAG, "Не удалось перезапустить сокет", e)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        socket?.close()
        resetPacing()
    }

    fun isClosed(): Boolean = closed || socket == null || (socket?.isClosed == true)

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        host = host,
        port = port,
        videoFramesSent = videoFramesSent.get(),
        videoPacketsSent = videoPacketsSent.get(),
        videoBytesSent = videoBytesSent.get(),
        probePacketsSent = probePacketsSent.get(),
        probeBytesSent = probeBytesSent.get(),
        sendErrors = sendErrors.get(),
        lastSendElapsedRealtimeMs = lastSendElapsedRealtimeMs,
    )

    private fun paceAfterPacket(packetBytes: Int) {
        pacingDebtBytes += packetBytes
        if (pacingDebtBytes < PACING_BURST_BYTES) return

        val burstBytes = pacingDebtBytes
        pacingDebtBytes = 0

        val nowNs = System.nanoTime()
        if (pacingNextSendNs == 0L || nowNs - pacingNextSendNs > PACING_IDLE_RESET_NS) {
            pacingNextSendNs = nowNs
        }

        val burstDurationNs = ((burstBytes.toLong() * 8L * 1_000_000_000L) / safePacingMaxBps.toLong())
            .coerceAtLeast(MIN_PACING_WAIT_NS)
        pacingNextSendNs += burstDurationNs

        val waitNs = pacingNextSendNs - nowNs
        if (waitNs > 0L) {
            sleepUntil(nowNs + waitNs)
        }
    }

    private fun sleepUntil(targetNs: Long) {
        while (true) {
            val remainingNs = targetNs - System.nanoTime()
            if (remainingNs <= 0L) return
            when {
                remainingNs > PARK_THRESHOLD_NS -> {
                    LockSupport.parkNanos((remainingNs - SPIN_GUARD_NS).coerceAtLeast(MIN_COMPAT_WAIT_NS))
                }
                remainingNs > YIELD_THRESHOLD_NS -> {
                    Thread.yield()
                }
                else -> {
                    LockSupport.parkNanos(remainingNs.coerceAtLeast(MIN_COMPAT_WAIT_NS))
                }
            }
        }
    }

    private fun resetPacing() {
        pacingDebtBytes = 0
        pacingNextSendNs = 0L
    }

    data class StatsSnapshot(
        val host: String,
        val port: Int,
        val videoFramesSent: Long,
        val videoPacketsSent: Long,
        val videoBytesSent: Long,
        val probePacketsSent: Long,
        val probeBytesSent: Long,
        val sendErrors: Long,
        val lastSendElapsedRealtimeMs: Long,
    )

    companion object {
        private const val MIN_UDP_PAYLOAD_BYTES: Int = 900
        private const val MAX_UDP_PAYLOAD_BYTES_LIMIT: Int = 61440
        private const val MIN_PACING_BPS: Int = 1_000_000
        private const val PACING_BURST_BYTES: Int = 4800
        private const val PACING_IDLE_RESET_NS: Long = 50_000_000L
        private const val MIN_PACING_WAIT_NS: Long = 250_000L
        private const val PARK_THRESHOLD_NS: Long = 2_000_000L
        private const val SPIN_GUARD_NS: Long = 300_000L
        private const val YIELD_THRESHOLD_NS: Long = 80_000L
        private const val MIN_COMPAT_WAIT_NS: Long = 20_000L
        private const val TAG = "UdpSender"
    }
}
