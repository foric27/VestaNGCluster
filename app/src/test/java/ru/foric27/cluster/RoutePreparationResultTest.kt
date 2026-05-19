package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePreparationResultTest {

    @Test
    fun `root unavailable maps to fallback status without iface failure`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.RootUnavailable(
                ifaceName = "eth0",
                details = "root недоступен",
            ),
        )

        assertTrue(result.rootRequired)
        assertTrue(result.ifacePresent)
        assertNull(result.bindIp)
        assertEquals("eth0", result.ifaceName)
        assertEquals("RootUnavailable", result.routePreparation.statusName)
    }

    @Test
    fun `iface missing keeps previous scheduling semantics`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.IfaceMissing(
                ifaceName = "eth0",
                details = "eth0_exists=false",
            ),
        )

        assertFalse(result.rootRequired)
        assertFalse(result.ifacePresent)
        assertNull(result.bindIp)
        assertEquals("IfaceMissing", result.routePreparation.statusName)
    }

    @Test
    fun `network unreachable blocks startup udp probe`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.NetworkUnreachable(
                ifaceName = "eth0",
                bindIp = "192.168.40.1",
                details = "маршрут недоступен",
            ),
        )

        assertFalse(result.rootRequired)
        assertTrue(result.ifacePresent)
        assertFalse(result.routeReady)
        assertEquals("192.168.40.1", result.bindIp)
        assertEquals("NetworkUnreachable", result.routePreparation.statusName)
    }

    @Test
    fun `success marks route ready for udp probe`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.Success(
                ifaceName = "eth0",
                bindIp = "192.168.40.1",
            ),
        )

        assertFalse(result.rootRequired)
        assertTrue(result.ifacePresent)
        assertTrue(result.routeReady)
        assertEquals("192.168.40.1", result.bindIp)
        assertEquals("Success", result.routePreparation.statusName)
    }

    @Test
    fun `route check failure exposes structured reason`() {
        val rootFailure = RootNetUtil.RouteCheckResult(
            iface = "eth0",
            dstIp = "192.168.40.2",
            expectedSrcIp = "192.168.40.1",
            rootRequired = true,
            routeCommandOk = false,
            devOk = false,
            srcOk = false,
            ok = false,
            output = "root недоступен",
        )
        val mismatch = rootFailure.copy(rootRequired = false, routeCommandOk = true, devOk = false)
        val noRoute = rootFailure.copy(rootRequired = false, routeCommandOk = false)

        assertEquals(RootNetUtil.FailureReason.ROOT_UNAVAILABLE, rootFailure.failureReason())
        assertEquals(RootNetUtil.FailureReason.ROUTE_NOT_APPLIED, mismatch.failureReason())
        assertEquals(RootNetUtil.FailureReason.NETWORK_UNREACHABLE, noRoute.failureReason())
    }
}
