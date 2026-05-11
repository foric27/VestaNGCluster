package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInterfaceSelectorTest {

    @Test
    fun `configured interface wins when present`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "eth0", "usb0"),
            preferredName = "eth0",
        )

        assertEquals("eth0", selection.name)
        assertEquals("configured", selection.source)
    }

    @Test
    fun `configured interface is reported missing when unavailable`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0"),
            preferredName = "eth0",
        )

        assertNull(selection.name)
        assertEquals("configured_missing", selection.source)
    }

    @Test
    fun `auto config selects known usb interface before wireless`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "rndis0", "rmnet_data0"),
            preferredName = "auto",
        )

        assertEquals("rndis0", selection.name)
        assertEquals("auto_usb", selection.source)
    }

    @Test
    fun `missing configured interface falls back to known usb interface`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "usb0"),
            preferredName = "eth0",
        )

        assertEquals("usb0", selection.name)
        assertEquals("configured_missing_auto_usb", selection.source)
    }

    @Test
    fun `auto config ignores mobile interfaces when no usb candidates exist`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "rmnet_data0", "lo"),
            preferredName = "   ",
        )

        assertNull(selection.name)
        assertEquals("auto_missing", selection.source)
    }

    @Test
    fun `auto config prefers usb0 over usb1 and wireless`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("usb1", "wlan0", "usb0"),
            preferredName = "auto",
        )

        assertEquals("usb0", selection.name)
        assertEquals("auto_usb", selection.source)
    }

    @Test
    fun `configured interface match is case insensitive`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("Eth0", "wlan0"),
            preferredName = "eth0",
        )

        assertEquals("Eth0", selection.name)
        assertEquals("configured", selection.source)
    }

    @Test
    fun `auto config can select known prefix interface`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "enx123456"),
            preferredName = "auto",
        )

        assertEquals("enx123456", selection.name)
        assertEquals("auto_usb", selection.source)
    }

    @Test
    fun `duplicate and blank names are normalized away`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("usb0", "USB0", "   ", "wlan0"),
            preferredName = "auto",
        )

        assertEquals("usb0", selection.name)
        assertEquals(listOf("usb0", "wlan0"), selection.available)
    }

    @Test
    fun `empty interface list reports auto missing`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = emptyList(),
            preferredName = "auto",
        )

        assertNull(selection.name)
        assertEquals("auto_missing", selection.source)
    }
}
