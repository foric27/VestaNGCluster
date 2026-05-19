package ru.foric27.cluster

import timber.log.Timber
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
internal class EmbeddedFtpServerFactory(
    private val interfaceIpv4sProvider: (String) -> List<String> = { interfaceName ->
        val networkInterface = NetworkInterface.getByName(interfaceName)
        if (networkInterface == null || !networkInterface.isUp || networkInterface.isLoopback) {
            emptyList()
        } else {
            sequence {
                val enumeration = networkInterface.inetAddresses
                while (enumeration.hasMoreElements()) {
                    yield(enumeration.nextElement())
                }
            }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toList()
        }
    },
    private val firstNonLoopbackIpv4Provider: () -> String? = {
        sequence<NetworkInterface> {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return@sequence
            while (enumeration.hasMoreElements()) {
                yield(enumeration.nextElement())
            }
        }
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { networkInterface ->
                sequence {
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        yield(inetAddresses.nextElement())
                    }
                }
            }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    },
    private val localIpv4Checker: (String) -> Boolean = { host ->
        sequence<NetworkInterface> {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return@sequence
            while (enumeration.hasMoreElements()) {
                yield(enumeration.nextElement())
            }
        }
            .flatMap { networkInterface ->
                sequence {
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        yield(inetAddresses.nextElement())
                    }
                }
            }
            .filterIsInstance<Inet4Address>()
            .any { address -> address.hostAddress == host }
    },
) {

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
        onTransferError: ((String) -> Unit)? = null,
    ): RunningServer {
        require(ftpRoot.exists() && ftpRoot.isDirectory) {
            "FTP-root не существует: ${ftpRoot.absolutePath}"
        }

        val resolvedAddress = resolveBindAddress(config)
        Timber.tag(TAG).i("FTP bind-адрес: ${resolvedAddress.host}:${config.ftpPort} (${resolvedAddress.reason})")

        val serverFactory = FtpServerFactory()
        serverFactory.connectionConfig = ConnectionConfigFactory().apply {
            isAnonymousLoginEnabled = config.ftpAnonymousEnabled
        }.createConnectionConfig()

        val listenerFactory = ListenerFactory().apply {
            port = config.ftpPort
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
        serverFactory.ftplets = mutableMapOf("updateLogger" to LoggingFtplet(onTransferError))

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

    internal fun resolveBindAddress(config: FtpServerConfig): ResolvedAddress {
        val explicitHost = config.ftpAdvertisedHost?.trim().orEmpty()
        val interfaceName = config.ftpInterfaceName?.trim().orEmpty()
        if (explicitHost.isNotEmpty()) {
            if (!isValidIpv4(explicitHost) || !localIpv4Checker(explicitHost)) {
                throw FtpException("Явный FTP host недоступен на текущем устройстве: $explicitHost")
            }

            if (interfaceName.isNotEmpty()) {
                val interfaceHosts = interfaceIpv4sProvider(interfaceName)
                if (interfaceHosts.isEmpty()) {
                    throw FtpException("FTP host $explicitHost ещё не назначен на интерфейс $interfaceName")
                }
                if (explicitHost !in interfaceHosts) {
                    throw FtpException("FTP host $explicitHost назначен не на интерфейс $interfaceName")
                }
                Timber.tag(TAG).i("Использую explicit FTP host $explicitHost на интерфейсе $interfaceName")
                return ResolvedAddress(explicitHost, "Явный локальный IP $explicitHost на интерфейсе $interfaceName")
            }

            Timber.tag(TAG).i("Использую локальный explicit FTP host: $explicitHost")
            return ResolvedAddress(explicitHost, "Явный локальный IP из конфигурации")
        }

        if (interfaceName.isNotEmpty()) {
            val interfaceHost = interfaceIpv4sProvider(interfaceName).firstOrNull()
            if (interfaceHost != null) {
                Timber.tag(TAG).i("Выбран интерфейс из конфигурации: $interfaceName -> $interfaceHost")
                return ResolvedAddress(interfaceHost, "Интерфейс $interfaceName")
            }
            Timber.tag(TAG).w("Интерфейс $interfaceName не найден или не содержит IPv4, перехожу к fallback")
        }

        val fallbackHost = firstNonLoopbackIpv4Provider()
            ?: throw FtpException("Не найден IPv4 для запуска FTP-сервера")
        Timber.tag(TAG).i("Использую первый подходящий non-loopback IPv4: $fallbackHost")
        return ResolvedAddress(fallbackHost, "Первый non-loopback IPv4")
    }

    private fun resolveInterfaceIpv4s(interfaceName: String): List<String> {
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return emptyList()
        if (!networkInterface.isUp || networkInterface.isLoopback) return emptyList()
        return networkInterface.inetAddressesSequence()
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toList()
    }

    private fun resolveFirstNonLoopbackIpv4(): String? {
        return networkInterfacesSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddressesSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun resolveLocalIpv4(host: String): Boolean {
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

    internal data class ResolvedAddress(
        val host: String,
        val reason: String,
    )

    private class LoggingFtplet(
        private val onTransferError: ((String) -> Unit)? = null,
    ) : DefaultFtplet() {
        override fun onConnect(session: FtpSession?): FtpletResult {
            val message = "FTP клиент подключился: ${session.clientAddress()}"
            Timber.tag(TAG).i(message)
            AppWarningCenter.publish(message)
            return FtpletResult.DEFAULT
        }

        override fun onDisconnect(session: FtpSession?): FtpletResult {
            Timber.tag(TAG).i("FTP клиент отключился: ${session.clientAddress()}")
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
                Timber.tag(TAG).i("FTP ${session.clientAddress()} -> $command$safeArgument")
                if (command == "RETR") {
                    Timber.tag(TAG).i("Начинаю передачу файла ${argument.orEmpty()}")
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
                Timber.tag(TAG).i("FTP ${session.clientAddress()} <- $command код=$code")
                if (command == "RETR") {
                    if (code >= 400) {
                        val errorMsg = "Передача файла прервана с ошибкой, код=$code"
                        Timber.tag(TAG).w(errorMsg)
                        onTransferError?.invoke(errorMsg)
                    } else {
                        Timber.tag(TAG).i("Передача файла завершена, код=$code")
                    }
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
