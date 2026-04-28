package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetworkAddressingTest {

    @Test
    fun `parseIpv4Cidr uses default prefix 24`() {
        val cidr = RootNetworkAddressing.parseIpv4Cidr("192.168.40.1")

        assertEquals(Ipv4Cidr(ip = "192.168.40.1", prefix = 24, network = "192.168.40.0"), cidr)
    }

    @Test
    fun `parseIpv4Cidr calculates prefix network`() {
        val cidr = RootNetworkAddressing.parseIpv4Cidr("10.10.15.200/20")

        assertEquals(Ipv4Cidr(ip = "10.10.15.200", prefix = 20, network = "10.10.0.0"), cidr)
    }

    @Test
    fun `parseIpv4Cidr rejects invalid input`() {
        assertNull(RootNetworkAddressing.parseIpv4Cidr("192.168.40.300/24"))
        assertNull(RootNetworkAddressing.parseIpv4Cidr("192.168.40.1/33"))
        assertNull(RootNetworkAddressing.parseIpv4Cidr("not-ip"))
    }

    @Test
    fun `isValidIpv4 validates octets`() {
        assertTrue(RootNetworkAddressing.isValidIpv4("0.1.254.255"))
        assertFalse(RootNetworkAddressing.isValidIpv4("0.1.254.256"))
        assertFalse(RootNetworkAddressing.isValidIpv4("0.1.254"))
    }
}
