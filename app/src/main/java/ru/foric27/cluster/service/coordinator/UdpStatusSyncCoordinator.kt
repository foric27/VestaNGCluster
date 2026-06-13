package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.service.*
import ru.foric27.cluster.util.CoroutineWorker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Координатор периодической отправки JSON-пакетов синхронизации на порт 5001.
 *
 * Публикует vid/time/lang через [DatagramSocket], привязанный к [bindIp].
 * Ключевые поля отправляются по таймеру или при изменении.
 * Слушает broadcast времени и языка для дедупликации.
 */
internal class UdpStatusSyncCoordinator(
    private val context: Context,
    private val tag: String,
    private val scope: CoroutineScope,
    private val statusKeySyncInterval: Int,
    private val statusPort: Int,
    private val statusPeriodMs: Int,
    private val statusErrorLogEvery: Int,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
) {

    /**
     * Снимок статистики отправки status sync.
     *
     * @property packetsSent количество отправленных пакетов
     * @property bytesSent количество отправленных байт
     * @property sendErrors количество ошибок отправки
     */
    data class StatsSnapshot(
        val packetsSent: Long,
        val bytesSent: Long,
        val sendErrors: Long,
    )

    private var statusJob: Job? = null
    @Volatile private var syncHandler: SyncHandler? = null
    @Volatile private var statusSocket: DatagramSocket? = null
    @Volatile private var statusReceiver: BroadcastReceiver? = null
    @Volatile private var statusReceiverRegistered = false
    private val statusPacketsSent = AtomicLong(0)
    private val statusBytesSent = AtomicLong(0)
    private val statusSendErrors = AtomicLong(0)

    /**
     * Запускает корутину отправки статуса.
     *
     * Создаёт [SyncHandler], регистрирует receiver для системных событий
     * и запускает фоновую корутину с периодической отправкой.
     *
     * @param bindIp локальный IP для привязки сокета или null
     * @param hostValue целевой хост для отправки статуса
     */
    fun start(bindIp: String?, hostValue: String) {
        try {
            stop()
            syncHandler = SyncHandler(context, statusKeySyncInterval)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED -> syncHandler?.setTimeChanged()
                        Intent.ACTION_LOCALE_CHANGED -> syncHandler?.setLangChanged()
                    }
                }
            }
            statusReceiver = receiver

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
            registerLocalReceiver(receiver, filter)
            statusReceiverRegistered = true

            statusPacketsSent.set(0)
            statusBytesSent.set(0)
            statusSendErrors.set(0)

            val socket = if (!bindIp.isNullOrBlank()) {
                try {
                    DatagramSocket(InetSocketAddress(InetAddress.getByName(bindIp), 0)).also {
                        Timber.tag(tag).i("StatusSocket привязан к локальному IP $bindIp")
                    }
                } catch (t: Throwable) {
                    Timber.tag(tag).w(t, "Не удалось привязать statusSocket к $bindIp, использую bind 0.0.0.0")
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            statusSocket = socket
            val destinationHost = InetAddress.getByName(hostValue)

            statusJob = CoroutineWorker.launchPeriodicWorker(
                scope = scope,
                name = "StatusSync",
                intervalMs = statusPeriodMs.toLong(),
                threadPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
            ) {
                var consecutiveErrors = 0
                try {
                    val payload = syncHandler?.sync()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    val packet = DatagramPacket(payload, payload.size, destinationHost, statusPort)
                    socket.send(packet)
                    statusPacketsSent.incrementAndGet()
                    statusBytesSent.addAndGet(payload.size.toLong())
                    consecutiveErrors = 0
                } catch (t: Throwable) {
                    statusSendErrors.incrementAndGet()
                    consecutiveErrors++
                    if (consecutiveErrors == 1 || consecutiveErrors % statusErrorLogEvery == 0) {
                        val suppressed = if (consecutiveErrors > 1) " (подавлено ${consecutiveErrors - 1})" else ""
                        Timber.tag(tag).w(t, "Ошибка отправки status sync (ошибок подряд=$consecutiveErrors)$suppressed")
                    }
                }
            }

            Timber.tag(tag).i("Status sync запущен (dst=$hostValue:$statusPort)")
        } catch (t: Throwable) {
            Timber.tag(tag).w(t, "Не удалось запустить status sync")
            stop()
        }
    }

    /**
     * Останавливает корутину отправки статуса и освобождает ресурсы.
     *
     * Закрывает сокет, снимает receiver и отменяет корутину.
     */
    fun stop() {
        statusJob?.cancel()
        statusJob = null

        try {
            statusSocket?.close()
        } catch (t: Throwable) {
            Timber.tag(tag).w(t, "Не удалось закрыть statusSocket")
        }
        statusSocket = null

        if (statusReceiverRegistered) {
            unregisterReceiverBestEffort(statusReceiver, "status sync")
            statusReceiverRegistered = false
        }
        statusReceiver = null
        syncHandler = null
    }

    /**
     * Возвращает снимок текущей статистики отправки статуса.
     *
     * @return [StatsSnapshot] с актуальными счётчиками
     */
    fun snapshot(): StatsSnapshot {
        return StatsSnapshot(
            packetsSent = statusPacketsSent.get(),
            bytesSent = statusBytesSent.get(),
            sendErrors = statusSendErrors.get(),
        )
    }
}
