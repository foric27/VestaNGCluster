package ru.foric27.cluster.service.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpNetworkPreparationCoordinatorTest {

    @Test
    fun `Success statusName bindIp and ifaceName`() {
        val result = RoutePreparationResult.Success(
            bindIp = "192.168.40.1",
            ifaceName = "eth0",
        )
        assertEquals("Success", result.statusName)
        assertEquals("192.168.40.1", result.bindIp)
        assertEquals("eth0", result.ifaceName)
    }

    @Test
    fun `IfaceMissing statusName with null bindIp`() {
        val result = RoutePreparationResult.IfaceMissing(
            ifaceName = "eth0",
            details = "not found",
        )
        assertEquals("IfaceMissing", result.statusName)
        assertNull(result.bindIp)
        assertEquals("eth0", result.ifaceName)
    }

    @Test
    fun `RootUnavailable statusName with null bindIp`() {
        val result = RoutePreparationResult.RootUnavailable(
            ifaceName = "eth0",
            details = "no root",
        )
        assertEquals("RootUnavailable", result.statusName)
        assertNull(result.bindIp)
    }

    @Test
    fun `RouteTimeout statusName`() {
        val result = RoutePreparationResult.RouteTimeout(
            ifaceName = "eth0",
            details = "timeout",
        )
        assertEquals("RouteTimeout", result.statusName)
    }

    @Test
    fun `RouteNotApplied statusName`() {
        val result = RoutePreparationResult.RouteNotApplied(
            ifaceName = "eth0",
            bindIp = "192.168.40.1",
            details = "failed",
        )
        assertEquals("RouteNotApplied", result.statusName)
    }

    @Test
    fun `NetworkUnreachable statusName`() {
        val result = RoutePreparationResult.NetworkUnreachable(
            ifaceName = "eth0",
            bindIp = "192.168.40.1",
            details = "unreachable",
        )
        assertEquals("NetworkUnreachable", result.statusName)
    }

    @Test
    fun `Success UdpNetworkPreparationResult derived properties`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.Success("192.168.40.1", "eth0"),
        )
        assertTrue(result.routeReady)
        assertTrue(result.ifacePresent)
        assertFalse(result.rootRequired)
    }

    @Test
    fun `IfaceMissing UdpNetworkPreparationResult derived properties`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.IfaceMissing("eth0", "not found"),
        )
        assertFalse(result.routeReady)
        assertFalse(result.ifacePresent)
        assertFalse(result.rootRequired)
    }

    @Test
    fun `RootUnavailable UdpNetworkPreparationResult derived properties`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.RootUnavailable("eth0", "no root"),
        )
        assertFalse(result.routeReady)
        assertTrue(result.ifacePresent)
        assertTrue(result.rootRequired)
    }

    @Test
    fun `RouteTimeout UdpNetworkPreparationResult derived properties`() {
        val result = UdpNetworkPreparationResult(
            RoutePreparationResult.RouteTimeout("eth0", "timeout"),
        )
        assertFalse(result.routeReady)
        assertTrue(result.ifacePresent)
        assertFalse(result.rootRequired)
    }
}
