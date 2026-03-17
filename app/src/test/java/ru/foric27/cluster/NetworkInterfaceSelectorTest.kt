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
            availableNames = listOf("wlan0", "usb0"),
            preferredName = "eth0",
        )

        assertNull(selection.name)
        assertEquals("configured_missing", selection.source)
    }

    @Test
    fun `empty config is reported as missing config`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("eth0"),
            preferredName = "   ",
        )

        assertNull(selection.name)
        assertEquals("missing_config", selection.source)
    }
}
