package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetUtilTest {

    @Test
    fun `link up определяется по lower_up`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP mode DEFAULT group default qlen 1000"

        assertTrue(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `link up определяется по carrier`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc fq_codel state DOWN mode DEFAULT group default qlen 1000\n1"

        assertTrue(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `link down определяется при отсутствии lower_up и carrier`() {
        val output = "7: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc noop state DOWN mode DEFAULT group default qlen 1000\n0"

        assertFalse(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `link up определяется по ifconfig running`() {
        val output = "eth0      Link encap:Ethernet  HWaddr 00:11:22:33:44:55\n          UP BROADCAST RUNNING MULTICAST  MTU:1500  Metric:1"

        assertTrue(RootNetUtil.isIfaceLinkUp(output))
    }

    @Test
    fun `interface batch starts with cleanup before setup`() {
        val commands = RootNetUtil.buildInterfaceSetupBatch(
            iface = "eth0",
            cidr = RootNetUtil.Ipv4Cidr(ip = "192.168.40.1", prefix = 24, network = "192.168.40.0"),
        )

        assertEquals("ip addr del 192.168.40.1/24 dev eth0", commands.first())
        assertEquals("ip link set eth0 up", commands[1])
        assertEquals("ip addr replace 192.168.40.1/24 dev eth0", commands[2])
    }

    @Test
    fun `routing batch contains cleanup and priorities 50 51 52`() {
        val commands = RootNetUtil.buildRoutingBatch(
            iface = "eth0",
            cidr = RootNetUtil.Ipv4Cidr(ip = "192.168.40.1", prefix = 24, network = "192.168.40.0"),
            gatewayIp = "192.168.40.2",
            includeFwmarkRule = true,
        )

        val cleanupToIndex = commands.indexOf("ip rule del to 192.168.40.2/32 lookup main priority 51")
        val addToIndex = commands.indexOf("ip rule add to 192.168.40.2/32 lookup main priority 51")
        val cleanupFromIndex = commands.indexOf("ip rule del from 192.168.40.1/32 lookup main priority 52")
        val addFromIndex = commands.indexOf("ip rule add from 192.168.40.1/32 lookup main priority 52")
        val cleanupFwmarkIndex = commands.indexOf("ip rule del fwmark 0x1 lookup main priority 50")
        val addFwmarkIndex = commands.indexOf("ip rule add fwmark 0x1 lookup main priority 50")

        assertTrue(cleanupToIndex in 0 until addToIndex)
        assertTrue(cleanupFromIndex in 0 until addFromIndex)
        assertTrue(cleanupFwmarkIndex in 0 until addFwmarkIndex)
    }

    @Test
    fun `iptables batch contains mark 0x1 cleanup then add`() {
        val commands = RootNetUtil.buildIptablesBatch("192.168.40.2")

        assertEquals("iptables -t mangle -D OUTPUT -d 192.168.40.2 -j MARK --set-mark 0x1", commands.first())
        assertEquals("iptables -t mangle -A OUTPUT -d 192.168.40.2 -j MARK --set-mark 0x1", commands.last())
    }
}
