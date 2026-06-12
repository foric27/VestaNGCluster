package ru.foric27.cluster.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.foric27.cluster.util.AppWarningCenter

class UdpServiceAlertsTest {

    @Before
    fun setUp() {
        AppWarningCenter.clear()
    }

    private fun createAlerts(
        notifications: MutableList<String> = mutableListOf(),
    ) = UdpServiceAlerts(
        context = null!!,
        tag = "test",
        updateNotification = { notifications.add(it) },
    )

    @Test
    fun `notifyNoLinkOnce calls updateNotification`() {
        val notifications = mutableListOf<String>()
        val alerts = createAlerts(notifications)
        alerts.notifyNoLinkOnce("no link")
        assertEquals(listOf("no link"), notifications)
    }

    @Test
    fun `notifyNoLinkOnce publishes warning only once`() {
        val alerts = createAlerts()
        alerts.notifyNoLinkOnce("no link")
        alerts.notifyNoLinkOnce("no link")
        alerts.notifyNoLinkOnce("no link")
        val warnings = AppWarningCenter.consumeAll()
        assertEquals(1, warnings.size)
        assertEquals("no link", warnings[0])
    }

    @Test
    fun `notifyNoLinkOnce updates notification every time`() {
        val notifications = mutableListOf<String>()
        val alerts = createAlerts(notifications)
        alerts.notifyNoLinkOnce("msg1")
        alerts.notifyNoLinkOnce("msg2")
        alerts.notifyNoLinkOnce("msg3")
        assertEquals(listOf("msg1", "msg2", "msg3"), notifications)
    }

    @Test
    fun `resetNoLinkWarning allows republishing`() {
        val alerts = createAlerts()
        alerts.notifyNoLinkOnce("no link")
        alerts.resetNoLinkWarning()
        alerts.notifyNoLinkOnce("no link again")
        val warnings = AppWarningCenter.consumeAll()
        assertEquals(2, warnings.size)
    }
}
