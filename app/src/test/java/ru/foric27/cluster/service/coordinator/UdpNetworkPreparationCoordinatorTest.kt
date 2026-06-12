package ru.foric27.cluster.service.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpNetworkPreparationCoordinatorTest {

    @Test
    fun `Success has correct statusName and properties`() {
        val result = RoutePreparationResult.Success("192.168.40.1", "eth0")
        assertEquals("Success", result.statusName)
        assertEquals("192.168.40.1", result.bindIp)
        assertEquals("eth0", result.ifaceName)

        val prep = UdpNetworkPreparationResult(result)
        assertTrue(prep.routeReady)
        assertTrue(prep.ifacePresent)
        assertFalse(prep.rootRequired)
        assertEquals("192.168.40.1", prep.bindIp)
        assertEquals("eth0", prep.ifaceName)
    }

    @Test
    fun `IfaceMissing has null bindIp and ifacePresent false`() {
        val result = RoutePreparationResult.IfaceMissing("eth0", "not found")
        assertEquals("IfaceMissing", result.statusName)
        assertNull(result.bindIp)
        assertEquals("eth0", result.ifaceName)

        val prep = UdpNetworkPreparationResult(result)
        assertFalse(prep.routeReady)
        assertFalse(prep.ifacePresent)
        assertFalse(prep.rootRequired)
    }

    @Test
    fun `RootUnavailable has rootRequired true`() {
        val result = RoutePreparationResult.RootUnavailable("eth0", "no root")
        assertEquals("RootUnavailable", result.statusName)
        assertNull(result.bindIp)

        val prep = UdpNetworkPreparationResult(result)
        assertFalse(prep.routeReady)
        assertTrue(prep.ifacePresent)
        assertTrue(prep.rootRequired)
    }

    @Test
    fun `RouteTimeout has correct properties`() {
        val result = RoutePreparationResult.RouteTimeout("eth0", "timeout")
        assertEquals("RouteTimeout", result.statusName)

        val prep = UdpNetworkPreparationResult(result)
        assertFalse(prep.routeReady)
        assertTrue(prep.ifacePresent)
        assertFalse(prep.rootRequired)
    }

    @Test
    fun `RouteNotApplied preserves bindIp`() {
        val result = RoutePreparationResult.RouteNotApplied("eth0", "192.168.40.1", "failed")
        assertEquals("RouteNotApplied", result.statusName)
        assertEquals("192.168.40.1", result.bindIp)

        val prep = UdpNetworkPreparationResult(result)
        assertFalse(prep.routeReady)
        assertTrue(prep.ifacePresent)
    }

    @Test
    fun `NetworkUnreachable preserves bindIp`() {
        val result = RoutePreparationResult.NetworkUnreachable("eth0", "192.168.40.1", "unreachable")
        assertEquals("NetworkUnreachable", result.statusName)
        assertEquals("192.168.40.1", result.bindIp)

        val prep = UdpNetworkPreparationResult(result)
        assertFalse(prep.routeReady)
        assertTrue(prep.ifacePresent)
    }

    @Test
    fun `data class equality works for Success`() {
        val a = RoutePreparationResult.Success("192.168.40.1", "eth0")
        val b = RoutePreparationResult.Success("192.168.40.1", "eth0")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class equality works for UdpNetworkPreparationResult`() {
        val a = UdpNetworkPreparationResult(RoutePreparationResult.Success("192.168.40.1", "eth0"))
        val b = UdpNetworkPreparationResult(RoutePreparationResult.Success("192.168.40.1", "eth0"))
        assertEquals(a, b)
    }
}
