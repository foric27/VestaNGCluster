package ru.foric27.cluster

import android.util.Log
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Создание встроенного Apache FtpServer с read-only доступом к подготовленной директории обновления.
 */
class EmbeddedFtpServerFactory {

    data class BoundAddress(
        val host: String,
        val port: Int,
    )

    data class RunningServer(
        val ftpServer: FtpServer,
        val boundAddress: BoundAddress,
    )

    fun create(
        config: FtpServerConfig,
        ftpRoot: File,
    ): RunningServer {
        require(ftpRoot.exists() && ftpRoot.isDirectory) {
            "FTP-root не существует: ${ftpRoot.absolutePath}"
        }

        val resolvedAddress = resolveBindAddress(config)
        Log.i(TAG, "FTP bind-адрес: ${resolvedAddress.host}:${config.ftpPort} (${resolvedAddress.reason})")

        val serverFactory = FtpServerFactory()
        serverFactory.connectionConfig = ConnectionConfigFactory().apply {
            isAnonymousLoginEnabled = config.ftpAnonymousEnabled
        }.createConnectionConfig()

        val listenerFactory = ListenerFactory().apply {
            port = config.ftpPort
            serverAddress = resolvedAddress.host
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passiveAddress = resolvedAddress.host
                passiveExternalAddress = resolvedAddress.host
                passivePorts = config.passivePortsSpec()
                isPassiveIpCheck = false
            }.createDataConnectionConfiguration()
        }
        serverFactory.addListener("default", listenerFactory.createListener())

        val fileSystemFactory = NativeFileSystemFactory().apply {
            isCreateHome = true
        }
        serverFactory.fileSystem = fileSystemFactory
        serverFactory.ftplets = mapOf("updateLogger" to LoggingFtplet())

        val user = BaseUser().apply {
            name = config.ftpUser
            password = config.ftpPassword
            homeDirectory = ftpRoot.absolutePath
            setEnabled(true)
            authorities = emptyList()
        }
        serverFactory.userManager.save(user)

        val ftpServer = serverFactory.createServer()
        return RunningServer(
            ftpServer = ftpServer,
            boundAddress = BoundAddress(
                host = resolvedAddress.host,
                port = config.ftpPort,
            ),
        )
    }

    private fun resolveBindAddress(config: FtpServerConfig): ResolvedAddress {
        val interfaceName = config.ftpInterfaceName?.trim().orEmpty()
        if (interfaceName.isNotEmpty()) {
            val interfaceHost = findInterfaceIpv4(interfaceName)
            if (interfaceHost != null) {
                Log.i(TAG, "Выбран интерфейс из конфигурации: $interfaceName -> $interfaceHost")
                return ResolvedAddress(interfaceHost, "Интерфейс $interfaceName")
            }
            Log.w(TAG, "Интерфейс $interfaceName не найден или не содержит IPv4, перехожу к fallback")
        }

        val explicitHost = config.ftpAdvertisedHost?.trim().orEmpty()
        if (explicitHost.isNotEmpty()) {
            if (isValidIpv4(explicitHost) && isLocalIpv4(explicitHost)) {
                Log.i(TAG, "Использую локальный explicit host: $explicitHost")
                return ResolvedAddress(explicitHost, "Явный локальный IP из конфигурации")
            }
            Log.w(TAG, "Явный FTP host недоступен на текущем устройстве: $explicitHost. Перехожу к fallback")
        }

        val fallbackHost = firstNonLoopbackIpv4()
            ?: throw FtpException("Не найден IPv4 для запуска FTP-сервера")
        Log.i(TAG, "Использую первый подходящий non-loopback IPv4: $fallbackHost")
        return ResolvedAddress(fallbackHost, "Первый non-loopback IPv4")
    }

    private fun findInterfaceIpv4(interfaceName: String): String? {
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
        if (!networkInterface.isUp || networkInterface.isLoopback) return null
        return networkInterface.inetAddressesSequence()
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun firstNonLoopbackIpv4(): String? {
        return networkInterfacesSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddressesSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun isLocalIpv4(host: String): Boolean {
        return networkInterfacesSequence()
            .flatMap { it.inetAddressesSequence() }
            .filterIsInstance<Inet4Address>()
            .any { it.hostAddress == host }
    }

    private fun networkInterfacesSequence(): Sequence<NetworkInterface> = sequence {
        val enumeration = NetworkInterface.getNetworkInterfaces() ?: return@sequence
        while (enumeration.hasMoreElements()) {
            yield(enumeration.nextElement())
        }
    }

    private fun NetworkInterface.inetAddressesSequence() = sequence {
        val enumeration = inetAddresses
        while (enumeration.hasMoreElements()) {
            yield(enumeration.nextElement())
        }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255
        }
    }

    private data class ResolvedAddress(
        val host: String,
        val reason: String,
    )

    private class LoggingFtplet : DefaultFtplet() {
        override fun onConnect(session: FtpSession?): FtpletResult {
            val message = "FTP клиент подключился: ${session.clientAddress()}"
            Log.i(TAG, message)
            AppWarningCenter.publish(message)
            return FtpletResult.DEFAULT
        }

        override fun onDisconnect(session: FtpSession?): FtpletResult {
            Log.i(TAG, "FTP клиент отключился: ${session.clientAddress()}")
            return FtpletResult.DEFAULT
        }

        override fun beforeCommand(session: FtpSession?, request: FtpRequest?): FtpletResult {
            val command = request?.command?.uppercase(Locale.US).orEmpty()
            if (command in LOGGED_COMMANDS) {
                val argument = request?.argument?.takeIf { it.isNotBlank() }
                val safeArgument = when (command) {
                    "PASS" -> " ***"
                    else -> argument?.let { " $it" } ?: ""
                }
                Log.i(TAG, "FTP ${session.clientAddress()} -> $command$safeArgument")
                if (command == "RETR") {
                    Log.i(TAG, "Начинаю передачу файла ${argument.orEmpty()}")
                }
            }
            return FtpletResult.DEFAULT
        }

        override fun afterCommand(
            session: FtpSession?,
            request: FtpRequest?,
            reply: FtpReply?,
        ): FtpletResult {
            val command = request?.command?.uppercase(Locale.US).orEmpty()
            if (command in LOGGED_COMMANDS) {
                val code = reply?.code ?: -1
                Log.i(TAG, "FTP ${session.clientAddress()} <- $command код=$code")
                if (command == "RETR") {
                    Log.i(TAG, "Передача файла завершена, код=$code")
                }
            }
            return FtpletResult.DEFAULT
        }

        private fun FtpSession?.clientAddress(): String {
            return this?.clientAddress?.toString() ?: "unknown"
        }
    }

    private companion object {
        private const val TAG = "EmbeddedFtpServer"
        private val LOGGED_COMMANDS = setOf("USER", "PASS", "TYPE", "SIZE", "PASV", "RETR", "QUIT")
    }
}
