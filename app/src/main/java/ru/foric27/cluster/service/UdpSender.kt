package ru.foric27.cluster.service
import ru.foric27.cluster.config.*
import ru.foric27.cluster.video.*

import timber.log.Timber
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
 * Размер одного UDP-пакета задаётся через RuntimeConfig. По умолчанию используется
 * значение, которое помещается в Ethernet MTU 1500 без IP-фрагментации.
 */
internal class UdpSender(
    private val host: String,
    private val port: Int,
    bindIp: String?,
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
    @Volatile private var lastSendAttemptElapsedRealtimeMs: Long = 0L
    @Volatile private var lastSendSuccessElapsedRealtimeMs: Long = 0L
    @Volatile private var consecutiveFrameSendErrors: Int = 0
    @Volatile private var lastProbeFailureLogElapsedRealtimeMs: Long = 0L
    @Volatile private var lastProbeFailureSignature: String? = null
    @Volatile private var suppressedProbeFailureCount: Int = 0
    @Volatile private var lastSendErrorLogElapsedRealtimeMs: Long = 0L
    @Volatile private var suppressedSendErrorCount: Int = 0

    @Volatile private var lastIframePayload: ByteArray? = null
    @Volatile private var lastIframeConfig: ByteArray? = null

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
                    Timber.tag(TAG).i("UDP-сокет привязан к локальному адресу $bindIp")
                }
            } catch (be: BindException) {
                Timber.tag(TAG).w(be, "Не удалось привязать UDP-сокет к $bindIp; fallback на 0.0.0.0")
                try {
                    DatagramSocket().also {
                        Timber.tag(TAG).i("UDP-сокет создан без привязки (fallback) для $host:$port")
                    }
                } catch (e: SocketException) {
                    throw IOException("Не удалось создать UDP-сокет даже с fallback", e)
                }
            }
        } else {
            DatagramSocket()
        }

        socket?.broadcast = false
        try {
            socket?.sendBufferSize = 1 shl 20
        } catch (_: Throwable) {
        }

        resetPacing()
        Timber.tag(TAG).i("UDP-сокет создан для $host:$port, payload=$safePayloadBytes, pacingMax=${safePacingMaxBps}бит/с")
    }

    /**
     * Отправляет видеокадр по UDP, фрагментируя при необходимости.
     *
     * Крупные буферы разбиваются на фрагменты размером [safePayloadBytes].
     * Выполняет pacing между пакетами для контроля битрейта.
     *
     * @param data байтовый массив видеокадра или null
     * @throws IOException при повторяющихся ошибках отправки
     * @throws SocketException если сокет закрыт
     */
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
                markSendAttempt()
                s.send(packet)
                videoPacketsSent.incrementAndGet()
                videoBytesSent.addAndGet(len.toLong())
                markSendSuccess()
                offset += len
                paceAfterPacket(len)
            }
        } catch (e: java.net.PortUnreachableException) {
            val failureCount = markFrameSendFailure()
            Timber.tag(TAG).w("UDP PortUnreachable (сеть недоступна), отправлено $offset/${data.size} байт")
            if (failureCount >= MAX_CONSECUTIVE_FRAME_SEND_ERRORS) {
                throw IOException("Повторяющаяся UDP PortUnreachable ошибка ($failureCount подряд)", e)
            }
        } catch (e: IOException) {
            val failureCount = markFrameSendFailure()
            val errorMsg = e.message?.lowercase() ?: ""
            val causeMsg = e.cause?.message?.lowercase() ?: ""
            val isInvalidArgument = errorMsg.contains("einval") || causeMsg.contains("einval")
            val isNetworkUnreachable = errorMsg.contains("enetunreach") || causeMsg.contains("enetunreach") || errorMsg.contains("unreachable")
            
            if (isInvalidArgument || isNetworkUnreachable) {
                // Сетевые ошибки: пробуем пересоздать сокет и продолжить
                // Не прокидываем ошибку в VideoEncoder — это transient network issue
                Timber.tag(TAG).w("UDP сетевая ошибка (${if (isInvalidArgument) "EINVAL" else "ENETUNREACH"}), пробуем пересоздать сокет")
                try {
                    socket?.close()
                    socket = if (bindIp != null && !isInvalidArgument) {
                        // Для ENETUNREACH пробуем без bind, т.к. bind-адрес может быть временно недоступен
                        DatagramSocket()
                    } else {
                        DatagramSocket()
                    }
                    socket?.broadcast = false
                    Timber.tag(TAG).i("UDP-сокет пересоздан для recovery от сетевой ошибки")
                    
                    // Повторяем отправку текущего пакета
                    val packet = DatagramPacket(data, offset, minOf(safePayloadBytes, data.size - offset), a, port)
                    markSendAttempt()
                    socket?.send(packet)
                    videoPacketsSent.incrementAndGet()
                    videoBytesSent.addAndGet(packet.length.toLong())
                    markSendSuccess()
                    offset += packet.length
                    paceAfterPacket(packet.length)
                    // Продолжаем отправку оставшихся фрагментов в while-цикле выше
                    return
                } catch (fallbackEx: Exception) {
                    Timber.tag(TAG).w(fallbackEx, "UDP fallback тоже не удался")
                }
            }
            
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val elapsedSinceLastLog = nowMs - lastSendErrorLogElapsedRealtimeMs
            val shouldLog = lastSendErrorLogElapsedRealtimeMs == 0L || elapsedSinceLastLog >= SEND_ERROR_LOG_WINDOW_MS
            if (shouldLog) {
                if (suppressedSendErrorCount > 0) {
                    Timber.tag(TAG).w("UDP send error подавлено $suppressedSendErrorCount раз; последняя: host=$host:$port, bindIp=${bindIp ?: "auto"}")
                    suppressedSendErrorCount = 0
                }
                Timber.tag(TAG).w(e, "UDP send error, host=$host:$port, bindIp=${bindIp ?: "auto"}, отправлено $offset/${data.size} байт")
                lastSendErrorLogElapsedRealtimeMs = nowMs
            } else {
                suppressedSendErrorCount++
            }
            
            // Для сетевых ошибок увеличиваем порог перед throw — это transient проблемы
            val maxErrors = if (isNetworkUnreachable) MAX_CONSECUTIVE_FRAME_SEND_ERRORS * 3 else MAX_CONSECUTIVE_FRAME_SEND_ERRORS
            if (failureCount >= maxErrors) {
                throw IOException("Повторяющаяся ошибка UDP-отправки ($failureCount подряд)", e)
            }
        } catch (e: RuntimeException) {
            sendErrors.incrementAndGet()
            Timber.tag(TAG).e(e, "UDP send runtime error")
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
            markSendAttempt()
            s.send(packet)
            probePacketsSent.incrementAndGet()
            probeBytesSent.addAndGet(payload.size.toLong())
            markSendSuccess()
            true
        } catch (t: Throwable) {
            sendErrors.incrementAndGet()
            logProbeFailure(t)
            false
        }
    }

    /**
     * Пересоздаёт UDP-сокет.
     *
     * Закрывает текущий сокет и создаёт новый через [createSocket].
     * При ошибке помечает sender как закрытый.
     */
    @Synchronized
    fun restart() {
        try {
            socket?.close()
            createSocket()
            Timber.tag(TAG).i("UDP-сокет перезапущен")
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Не удалось перезапустить UDP-сокет")
            closed = true // Гарантируем что isClosed() вернёт true при неудачном restart
        }
    }

    /**
     * Закрывает UDP-сокет и помечает sender как закрытый.
     *
     * После вызова [isClosed] вернёт true.
     */
    @Synchronized
    fun close() {
        closed = true
        socket?.close()
        resetPacing()
    }

    /**
     * Проверяет, закрыт ли sender или его сокет.
     *
     * @return true если сокет закрыт или null
     */
    fun isClosed(): Boolean = closed || socket == null || (socket?.isClosed == true)

    /**
     * Сохраняет последний I-frame для повторной отправки новым клиентам.
     *
     * @param payload полный I-frame включая SPS/PPS (Annex B)
     * @param config SPS/PPS конфигурация (может быть null если уже в payload)
     */
    fun bufferIframe(payload: ByteArray, config: ByteArray?) {
        lastIframePayload = payload
        lastIframeConfig = config
    }

    /**
     * Повторно отправляет последний I-frame (keep-alive / new client).
     *
     * Если I-frame ещё не был буферизован — ничего не делает.
     *
     * @return true если I-frame был отправлен
     */
    @Synchronized
    fun resendLastIframe(): Boolean {
        val payload = lastIframePayload ?: return false
        val config = lastIframeConfig
        try {
            if (config != null && config.isNotEmpty()) {
                sendFrame(config)
            }
            sendFrame(payload)
            Timber.tag(TAG).d("I-frame переотправлен (%d байт)", payload.size)
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Не удалось переотправить I-frame")
            return false
        }
    }

    /**
     * Проверяет, есть ли буферизованный I-frame.
     */
    fun hasBufferedIframe(): Boolean = lastIframePayload != null

    /**
     * Возвращает снимок текущей статистики отправки.
     *
     * @return объект [StatsSnapshot] с актуальными счётчиками
     */
    fun snapshot(): StatsSnapshot = StatsSnapshot(
        host = host,
        port = port,
        videoFramesSent = videoFramesSent.get(),
        videoPacketsSent = videoPacketsSent.get(),
        videoBytesSent = videoBytesSent.get(),
        probePacketsSent = probePacketsSent.get(),
        probeBytesSent = probeBytesSent.get(),
        sendErrors = sendErrors.get(),
        consecutiveFrameSendErrors = consecutiveFrameSendErrors,
        lastSendAttemptElapsedRealtimeMs = lastSendAttemptElapsedRealtimeMs,
        lastSendSuccessElapsedRealtimeMs = lastSendSuccessElapsedRealtimeMs,
    )

    private fun markSendAttempt() {
        lastSendAttemptElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun markSendSuccess() {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        lastSendAttemptElapsedRealtimeMs = nowMs
        lastSendSuccessElapsedRealtimeMs = nowMs
        consecutiveFrameSendErrors = 0
        lastProbeFailureLogElapsedRealtimeMs = 0L
        lastProbeFailureSignature = null
        suppressedProbeFailureCount = 0
        lastSendErrorLogElapsedRealtimeMs = 0L
        suppressedSendErrorCount = 0
    }

    private fun logProbeFailure(error: Throwable) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val currentSignature = buildString {
            append(error.javaClass.name)
            append(':')
            append(error.message.orEmpty())
        }
        val sameFailure = currentSignature == lastProbeFailureSignature
        val withinWindow = sameFailure &&
            lastProbeFailureLogElapsedRealtimeMs > 0L &&
            (nowMs - lastProbeFailureLogElapsedRealtimeMs) < PROBE_FAILURE_LOG_WINDOW_MS

        if (withinWindow) {
            suppressedProbeFailureCount += 1
            return
        }

        val hostLabel = "$host:$port"
        val bindLabel = bindIp ?: "auto"
        if (sameFailure && suppressedProbeFailureCount > 0) {
            Timber.tag(TAG).w(
                "Проверочная отправка UDP всё ещё не удаётся: host=$hostLabel, bindIp=$bindLabel, repeats=${suppressedProbeFailureCount + 1}, cause=${error.message ?: error.javaClass.simpleName}",
            )
        } else {
            Timber.tag(TAG).w(error, "Проверочная отправка UDP не удалась: host=$hostLabel, bindIp=$bindLabel")
        }

        lastProbeFailureLogElapsedRealtimeMs = nowMs
        lastProbeFailureSignature = currentSignature
        suppressedProbeFailureCount = 0
    }

    private fun markFrameSendFailure(): Int {
        sendErrors.incrementAndGet()
        consecutiveFrameSendErrors += 1
        return consecutiveFrameSendErrors
    }

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
            // Потолок: не ждать дольше PACING_MAX_WAIT_NS, чтобы не блокировать codec thread
            val cappedWaitNs = waitNs.coerceAtMost(PACING_MAX_WAIT_NS)
            sleepUntil(nowNs + cappedWaitNs)
            if (cappedWaitNs < waitNs) {
                // Сбрасываем pacing, если пришлось обрезать ожидание
                pacingNextSendNs = System.nanoTime()
            }
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

    /**
     * Снимок статистики UDP-отправки.
     *
     * @param host целевой хост
     * @param port целевой порт
     * @param videoFramesSent количество отправленных видеокадров
     * @param videoPacketsSent количество отправленных видеопакетов
     * @param videoBytesSent количество отправленных видеобайт
     * @param probePacketsSent количество отправленных пробных пакетов
     * @param probeBytesSent количество отправленных пробных байт
     * @param sendErrors количество ошибок отправки
     * @param consecutiveFrameSendErrors количество последовательных ошибок отправки кадров
     * @param lastSendAttemptElapsedRealtimeMs время последней попытки отправки (elapsedRealtime)
     * @param lastSendSuccessElapsedRealtimeMs время последней успешной отправки (elapsedRealtime)
     */
    data class StatsSnapshot(
        val host: String,
        val port: Int,
        val videoFramesSent: Long,
        val videoPacketsSent: Long,
        val videoBytesSent: Long,
        val probePacketsSent: Long,
        val probeBytesSent: Long,
        val sendErrors: Long,
        val consecutiveFrameSendErrors: Int,
        val lastSendAttemptElapsedRealtimeMs: Long,
        val lastSendSuccessElapsedRealtimeMs: Long,
    )

    companion object {
        private const val MIN_UDP_PAYLOAD_BYTES: Int = 900
        private const val MAX_UDP_PAYLOAD_BYTES_LIMIT: Int = 61440
        private const val MIN_PACING_BPS: Int = 1_000_000
        private const val PACING_BURST_BYTES: Int = 4800
        private const val PACING_IDLE_RESET_NS: Long = 50_000_000L
        private const val PACING_MAX_WAIT_NS: Long = 10_000_000L // 10ms ceiling для paceAfterPacket
        private const val MIN_PACING_WAIT_NS: Long = 250_000L
        private const val PARK_THRESHOLD_NS: Long = 2_000_000L
        private const val SPIN_GUARD_NS: Long = 300_000L
        private const val YIELD_THRESHOLD_NS: Long = 80_000L
        private const val MIN_COMPAT_WAIT_NS: Long = 20_000L
        private const val MAX_CONSECUTIVE_FRAME_SEND_ERRORS: Int = 3
        private const val PROBE_FAILURE_LOG_WINDOW_MS: Long = 5_000L
        private const val SEND_ERROR_LOG_WINDOW_MS: Long = 5_000L
        private const val TAG = "UdpSender"
    }
}
