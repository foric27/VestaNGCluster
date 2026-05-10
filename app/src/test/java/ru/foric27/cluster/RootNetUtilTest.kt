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
            cidr = Ipv4Cidr(ip = "192.168.40.1", prefix = 24, network = "192.168.40.0"),
        )

        assertEquals("ip addr del 192.168.40.1/24 dev eth0", commands.first())
        assertEquals("ip link set eth0 up", commands[1])
        assertEquals("ip addr replace 192.168.40.1/24 dev eth0", commands[2])
    }

    @Test
    fun `routing batch contains cleanup and priorities 50 51 52`() {
        val commands = RootNetUtil.buildRoutingBatch(
            iface = "eth0",
            cidr = Ipv4Cidr(ip = "192.168.40.1", prefix = 24, network = "192.168.40.0"),
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
    fun `route planner builds commands without shell execution`() {
        val plan = RootNetworkRoutePlanner.plan(
            iface = " eth0 ",
            localCidr = "192.168.40.1/24",
            gatewayIp = "192.168.40.2",
            routingTable = "100",
            includeFwmarkRule = false,
        ).getOrThrow()

        assertEquals("eth0", plan.iface)
        assertEquals("100", plan.routingTable)
        // После удаления диагностических команд ip route replace на позиции 3 (было 8)
        assertEquals("ip route replace 192.168.40.0/24 dev eth0 scope link src 192.168.40.1 table 100", plan.commands[3])
        assertFalse(plan.commands.any { it.contains("fwmark") })
        // Удалённые команды больше не присутствуют
        assertFalse(plan.commands.any { it.contains("ip route flush cache") })
        assertFalse(plan.commands.any { it.contains("ip route get") })
        assertFalse(plan.commands.any { it.contains("ip rule show") })
        assertFalse(plan.commands.any { it.contains("ip route show") })
        assertFalse(plan.commands.any { it.contains("11000") || it.contains("11001") })
    }

    @Test
    fun `route planner pins target host to selected iface without iptables`() {
        val plan = RootNetworkRoutePlanner.plan(
            iface = "usb0",
            localCidr = "192.168.40.1/24",
            gatewayIp = "192.168.40.2",
            includeFwmarkRule = false,
        ).getOrThrow()

        assertTrue(plan.commands.contains("ip route replace 192.168.40.2/32 dev usb0 scope link src 192.168.40.1 table main"))
        assertTrue(plan.commands.contains("ip rule add to 192.168.40.2/32 lookup main priority 51"))
        assertTrue(plan.commands.contains("ip rule add from 192.168.40.1/32 lookup main priority 52"))
        assertFalse(plan.commands.any { it.contains("iptables") || it.contains("fwmark") })
    }

    @Test
    fun `route planner rejects invalid local cidr and target ip`() {
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/33",
                gatewayIp = "192.168.40.2",
                includeFwmarkRule = true,
            ).isFailure,
        )
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.040.2",
                includeFwmarkRule = true,
            ).isFailure,
        )
    }

    @Test
    fun `route planner rejects gateway outside subnet and reserved broadcast addresses`() {
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.41.2",
                includeFwmarkRule = true,
            ).isFailure,
        )
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.40.255",
                includeFwmarkRule = true,
            ).isFailure,
        )
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.40.0",
                includeFwmarkRule = true,
            ).isFailure,
        )
    }

    @Test
    fun `route planner rejects missing iface and invalid routing table`() {
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "   ",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.40.2",
                includeFwmarkRule = true,
            ).isFailure,
        )
        assertTrue(
            RootNetworkRoutePlanner.plan(
                iface = "eth0",
                localCidr = "192.168.40.1/24",
                gatewayIp = "192.168.40.2",
                routingTable = "0",
                includeFwmarkRule = true,
            ).isFailure,
        )
    }

    @Test
    fun `iptables batch contains mark 0x1 cleanup then add`() {
        val commands = RootNetUtil.buildIptablesBatch("192.168.40.2")

        assertEquals("iptables -t mangle -D OUTPUT -d 192.168.40.2 -j MARK --set-mark 0x1", commands.first())
        assertEquals("iptables -t mangle -A OUTPUT -d 192.168.40.2 -j MARK --set-mark 0x1", commands.last())
    }
}
