package ru.foric27.cluster.update

import org.junit.Assert.assertEquals
import org.junit.Test

class FtpServerConfigTest {

    @Test(expected = IllegalArgumentException::class)
    fun `port 0 throws`() {
        FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port 65536 throws`() {
        FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 65536,
        )
    }

    @Test
    fun `passivePortsSpec for range`() {
        val config = FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 2121,
            ftpPassivePorts = 30000..30100,
        )
        assertEquals("30000-30100", config.passivePortsSpec())
    }

    @Test
    fun `passivePortsSpec for single port`() {
        val config = FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 2121,
            ftpPassivePorts = 30000..30000,
        )
        assertEquals("30000", config.passivePortsSpec())
    }

    @Test
    fun `data class equals works`() {
        val a = FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 2121,
        )
        val b = FtpServerConfig(
            ftpInterfaceName = "eth0",
            ftpAdvertisedHost = "192.168.40.1",
            ftpPort = 2121,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different configs are not equal`() {
        val a = FtpServerConfig(ftpInterfaceName = "eth0", ftpAdvertisedHost = "192.168.40.1", ftpPort = 2121)
        val b = FtpServerConfig(ftpInterfaceName = "eth0", ftpAdvertisedHost = "192.168.40.1", ftpPort = 2122)
        assert(a != b)
    }
}
