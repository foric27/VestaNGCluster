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
}
