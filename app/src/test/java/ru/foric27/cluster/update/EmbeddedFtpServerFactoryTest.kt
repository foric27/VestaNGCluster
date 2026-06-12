package ru.foric27.cluster.update

import org.apache.ftpserver.ftplet.FtpException
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedFtpServerFactoryTest {

    @Test
    fun `explicit host is accepted when assigned on configured iface`() {
        val factory = EmbeddedFtpServerFactory(
            interfaceIpv4sProvider = { ifaceName ->
                when (ifaceName) {
                    "eth0" -> listOf("192.168.40.1")
                    else -> emptyList()
                }
            },
            firstNonLoopbackIpv4Provider = { "10.0.0.5" },
            localIpv4Checker = { host -> host == "192.168.40.1" },
        )

        val resolved = factory.resolveBindAddress(
            FtpServerConfig(
                ftpInterfaceName = "eth0",
                ftpAdvertisedHost = "192.168.40.1",
            ),
        )

        assertEquals("192.168.40.1", resolved.host)
        assertEquals("Явный локальный IP 192.168.40.1 на интерфейсе eth0", resolved.reason)
    }

    @Test(expected = FtpException::class)
    fun `explicit host is rejected until assigned on configured iface`() {
        val factory = EmbeddedFtpServerFactory(
            interfaceIpv4sProvider = { emptyList() },
            firstNonLoopbackIpv4Provider = { "10.0.0.5" },
            localIpv4Checker = { host -> host == "192.168.40.1" },
        )

        factory.resolveBindAddress(
            FtpServerConfig(
                ftpInterfaceName = "eth0",
                ftpAdvertisedHost = "192.168.40.1",
            ),
        )
    }

}
