package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.Process
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class UdpStatusSyncSnapshot(
    val packetsSent: Long,
    val bytesSent: Long,
    val sendErrors: Long,
)

internal class UdpStatusSyncCoordinator(
    private val context: Context,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val launchWorker: (name: String, threadPriority: Int, block: () -> Unit) -> Thread,
    private val interruptThreadQuietly: (Thread?, String) -> Unit,
    private val joinThreadQuietly: (Thread?, String) -> Unit,
) {

    private var statusThread: Thread? = null
    private val statusStop = AtomicBoolean(false)
    private var syncHandler: SyncHandler? = null
    private var statusSocket: DatagramSocket? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var statusReceiverRegistered = false
    private val statusPacketsSent = AtomicLong(0)
    private val statusBytesSent = AtomicLong(0)
    private val statusSendErrors = AtomicLong(0)

    fun start(network: Network?, bindIp: String?, hostValue: String) {
        try {
            stop()
            syncHandler = SyncHandler(context, STATUS_KEY_SYNC_INTERVAL)

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
                        Log.i(TAG, "StatusSocket привязан к локальному IP $bindIp")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Не удалось привязать statusSocket к $bindIp, использую bind 0.0.0.0", t)
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            statusSocket = socket
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (t: Throwable) {
                    Log.w(TAG, "Не удалось привязать statusSocket к Network (продолжаю без привязки): $t")
                }
            }

            val destinationHost = InetAddress.getByName(hostValue)
            statusThread = launchWorker("StatusSync", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                var consecutiveErrors = 0
                while (!statusStop.get()) {
                    try {
                        val payload = syncHandler?.sync()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        val packet = DatagramPacket(payload, payload.size, destinationHost, STATUS_PORT)
                        socket.send(packet)
                        statusPacketsSent.incrementAndGet()
                        statusBytesSent.addAndGet(payload.size.toLong())
                        consecutiveErrors = 0
                    } catch (t: Throwable) {
                        statusSendErrors.incrementAndGet()
                        consecutiveErrors++
                        if (consecutiveErrors == 1 || consecutiveErrors % STATUS_ERROR_LOG_EVERY == 0) {
                            Log.w(TAG, "Ошибка отправки status sync (ошибок подряд=$consecutiveErrors)", t)
                        }
                    }

                    try {
                        Thread.sleep(STATUS_PERIOD_MS.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            Log.i(TAG, "Status sync запущен (dst=$hostValue:$STATUS_PORT)")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запустить status sync", t)
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
            Log.w(TAG, "Не удалось закрыть statusSocket", t)
        }
        statusSocket = null

        if (statusReceiverRegistered) {
            unregisterReceiverBestEffort(statusReceiver, "status sync")
            statusReceiverRegistered = false
        }
        statusReceiver = null
        syncHandler = null
    }

    fun snapshot(): UdpStatusSyncSnapshot {
        return UdpStatusSyncSnapshot(
            packetsSent = statusPacketsSent.get(),
            bytesSent = statusBytesSent.get(),
            sendErrors = statusSendErrors.get(),
        )
    }

    private companion object {
        private const val TAG = "UdpStatusSyncCoord"
        private const val STATUS_PORT = 5001
        private const val STATUS_PERIOD_MS = 250
        private const val STATUS_KEY_SYNC_INTERVAL = 2400
        private const val STATUS_ERROR_LOG_EVERY = 10
    }
}
