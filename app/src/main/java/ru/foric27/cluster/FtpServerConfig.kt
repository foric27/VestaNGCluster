package ru.foric27.cluster

/**
 * Конфигурация встроенного FTP-сервера обновления.
 *
 * Значения по умолчанию берутся из действующей сетевой конфигурации проекта,
 * чтобы не дублировать IP и имя интерфейса в нескольких местах.
 */
data class FtpServerConfig(
    val ftpInterfaceName: String?,
    val ftpAdvertisedHost: String?,
    val ftpPort: Int = RuntimeConfig.UpdateFtp.PORT,
    val ftpPassivePorts: IntRange = RuntimeConfig.UpdateFtp.PASSIVE_PORT_START..RuntimeConfig.UpdateFtp.PASSIVE_PORT_END,
    val ftpUser: String = RuntimeConfig.UpdateFtp.USER,
    val ftpPassword: String = RuntimeConfig.UpdateFtp.PASSWORD,
    val ftpAnonymousEnabled: Boolean = true,
) {

    init {
        require(ftpPort in 1..65535) { "FTP-порт должен быть в диапазоне 1..65535" }
        require(!ftpPassivePorts.isEmpty()) { "Диапазон пассивных портов не должен быть пустым" }
    }

    fun passivePortsSpec(): String = if (ftpPassivePorts.first == ftpPassivePorts.last) {
        ftpPassivePorts.first.toString()
    } else {
        "${ftpPassivePorts.first}-${ftpPassivePorts.last}"
    }

    companion object {
        fun fromProject(): FtpServerConfig {
            return FtpServerConfig(
                ftpInterfaceName = RuntimeConfig.Root.IFACE,
                ftpAdvertisedHost = RuntimeConfig.Network.BIND_IP,
                ftpPort = RuntimeConfig.UpdateFtp.PORT,
                ftpPassivePorts = RuntimeConfig.UpdateFtp.PASSIVE_PORT_START..RuntimeConfig.UpdateFtp.PASSIVE_PORT_END,
                ftpUser = RuntimeConfig.UpdateFtp.USER,
                ftpPassword = RuntimeConfig.UpdateFtp.PASSWORD,
                ftpAnonymousEnabled = true,
            )
        }
    }
}
