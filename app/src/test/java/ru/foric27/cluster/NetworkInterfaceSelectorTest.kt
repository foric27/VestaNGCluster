package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfaceSelectorTest {

    @Test
    fun `auto mode prefers ccmni lan when higher priority interfaces are absent`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "ccmni2", "ccmni-lan", "lo"),
            preferredName = "auto",
        )

        assertEquals("ccmni-lan", selection.name)
        assertEquals("auto", selection.source)
    }

    @Test
    fun `configured interface wins when present`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "usb0", "ccmni-lan"),
            preferredName = "usb0",
        )

        assertEquals("usb0", selection.name)
        assertEquals("configured", selection.source)
    }

    @Test
    fun `root output parser keeps interface names and drops numeric indexes`() {
        val parsed = NetworkInterfaceSelector.parseInterfaceNames(
            """
            1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536
            35: ccmni-lan: <UP,LOWER_UP> mtu 1500
            37: wlan0: <UP,LOWER_UP> mtu 1500
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            ccmni-lan: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
            wlan0: 100 10 0 0 0 0 0 0 100 10 0 0 0 0 0 0
            """.trimIndent(),
        )

        assertTrue(parsed.contains("ccmni-lan"))
        assertTrue(parsed.contains("wlan0"))
        assertTrue(parsed.contains("lo"))
        assertFalse(parsed.contains("1"))
        assertFalse(parsed.contains("35"))
        assertFalse(parsed.contains("37"))
    }

    @Test
    fun `selection reports not found when nothing suitable exists`() {
        val selection = NetworkInterfaceSelector.selectFromAvailable(
            availableNames = listOf("wlan0", "lo"),
            preferredName = "auto",
        )

        assertNull(selection.name)
        assertEquals("not_found", selection.source)
    }
}
