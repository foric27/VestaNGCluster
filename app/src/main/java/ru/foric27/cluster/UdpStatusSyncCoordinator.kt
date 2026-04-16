package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class UdpStatusSyncCoordinator(
    private val context: Context,
    private val tag: String,
    private val statusKeySyncInterval: Int,
    private val statusPort: Int,
    private val statusPeriodMs: Int,
    private val statusErrorLogEvery: Int,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val launchWorker: (String, Int, () -> Unit) -> Thread,
    private val interruptThreadQuietly: (Thread?, String) -> Unit,
    private val joinThreadQuietly: (Thread?, String) -> Unit,
) {

    data class StatsSnapshot(
        val packetsSent: Long,
        val bytesSent: Long,
        val sendErrors: Long,
    )

    private var statusThread: Thread? = null
    private val statusStop = AtomicBoolean(false)
    private var syncHandler: SyncHandler? = null
    private var statusSocket: DatagramSocket? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var statusReceiverRegistered = false
    private val statusPacketsSent = AtomicLong(0)
    private val statusBytesSent = AtomicLong(0)
    private val statusSendErrors = AtomicLong(0)

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
            statusStop.set(false)

            val socket = if (!bindIp.isNullOrBlank()) {
                try {
                    DatagramSocket(InetSocketAddress(InetAddress.getByName(bindIp), 0)).also {
                        Log.i(tag, "StatusSocket привязан к локальному IP $bindIp")
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "Не удалось привязать statusSocket к $bindIp, использую bind 0.0.0.0", t)
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            statusSocket = socket
            val destinationHost = InetAddress.getByName(hostValue)

            statusThread = launchWorker("StatusSync", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                var consecutiveErrors = 0
                while (!statusStop.get()) {
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
                            Log.w(tag, "Ошибка отправки status sync (ошибок подряд=$consecutiveErrors)", t)
                        }
                    }

                    try {
                        Thread.sleep(statusPeriodMs.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            Log.i(tag, "Status sync запущен (dst=$hostValue:$statusPort)")
        } catch (t: Throwable) {
            Log.w(tag, "Не удалось запустить status sync", t)
            stop()
        }
    }

    fun stop() {
        statusStop.set(true)
        interruptThreadQuietly(statusThread, "status sync")
        joinThreadQuietly(statusThread, "status sync")
        statusThread = null

        try {
            statusSocket?.close()
        } catch (t: Throwable) {
            Log.w(tag, "Не удалось закрыть statusSocket", t)
        }
        statusSocket = null

        if (statusReceiverRegistered) {
            unregisterReceiverBestEffort(statusReceiver, "status sync")
            statusReceiverRegistered = false
        }
        statusReceiver = null
        syncHandler = null
    }

    fun snapshot(): StatsSnapshot {
        return StatsSnapshot(
            packetsSent = statusPacketsSent.get(),
            bytesSent = statusBytesSent.get(),
            sendErrors = statusSendErrors.get(),
        )
    }
}
