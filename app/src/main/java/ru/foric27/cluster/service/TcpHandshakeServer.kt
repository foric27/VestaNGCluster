package ru.foric27.cluster.service

import timber.log.Timber
import java.net.ServerSocket
import java.net.SocketException

/**
 * TCP-сервер для обнаружения подключения приёмника кластера.
 *
 * Слушает порт 5151. Когда приёмник подключается — логирует событие.
 * Когда отключается — уведомляет callback для potential recovery.
 *
 * Паттерн из OEM ClusterRendererServiceExtension: ServerSocket на порту 5151
 * для handshake/disconnect detection.
 */
internal class TcpHandshakeServer(
    private val port: Int = DEFAULT_PORT,
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
) {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var running = false

    /**
     * Запускает TCP-сервер в фоновом потоке.
     *
     * Если порт уже занят — логирует ошибку и не падает.
     */
    fun start() {
        if (running) return
        running = true
        serverThread = Thread({
            runServer()
        }, "TcpHandshake").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        Timber.tag(TAG).i("TCP handshake сервер запущен на порту $port")
    }

    /**
     * Останавливает TCP-сервер и закрывает сокет.
     */
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverThread?.interrupt()
        serverThread = null
        Timber.tag(TAG).i("TCP handshake сервер остановлен")
    }

    fun isRunning(): Boolean = running

    private fun runServer() {
        try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Не удалось открыть TCP handshake порт $port")
            running = false
            return
        }

        while (running) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                val clientAddr = clientSocket.inetAddress?.hostAddress ?: "unknown"
                Timber.tag(TAG).i("TCP handshake: клиент подключился: $clientAddr")
                runCatching { onClientConnected() }

                // Читаем пока клиент не отключится
                try {
                    val input = clientSocket.getInputStream()
                    val buffer = ByteArray(1024)
                    while (running && !clientSocket.isClosed) {
                        val read = input.read(buffer)
                        if (read == -1) break
                    }
                } catch (_: Throwable) {}

                runCatching { clientSocket.close() }
                Timber.tag(TAG).i("TCP handshake: клиент отключился: $clientAddr")
                runCatching { onClientDisconnected() }
            } catch (e: SocketException) {
                if (running) {
                    Timber.tag(TAG).w(e, "TCP handshake socket error")
                }
            } catch (e: Exception) {
                if (running) {
                    Timber.tag(TAG).w(e, "TCP handshake ошибка")
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 5151
        private const val TAG = "TcpHandshake"
    }
}
