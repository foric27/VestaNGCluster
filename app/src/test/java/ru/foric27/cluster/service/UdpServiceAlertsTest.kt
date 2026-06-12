package ru.foric27.cluster.service

import android.content.Context
import android.content.ContextWrapper
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

    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    private fun createAlerts(
        updateNotification: (String) -> Unit = {},
    ) = UdpServiceAlerts(
        context = fakeContext(),
        tag = "UdpServiceAlertsTest",
        updateNotification = updateNotification,
    )

    @Test
    fun `notifyNoLinkOnce calls updateNotification on first call`() {
        var notifiedMessage: String? = null
        val alerts = createAlerts(updateNotification = { notifiedMessage = it })

        alerts.notifyNoLinkOnce("no link")

        assertEquals("no link", notifiedMessage)
    }

    @Test
    fun `notifyNoLinkOnce calls updateNotification on second call too`() {
        var callCount = 0
        val alerts = createAlerts(updateNotification = { callCount++ })

        alerts.notifyNoLinkOnce("no link")
        alerts.notifyNoLinkOnce("no link")

        assertEquals(2, callCount)
    }

    @Test
    fun `notifyNoLinkOnce publishes to AppWarningCenter only once`() {
        val alerts = createAlerts()

        alerts.notifyNoLinkOnce("no link")
        alerts.notifyNoLinkOnce("no link")

        assertTrue(AppWarningCenter.contains("no link"))
        assertEquals(1, AppWarningCenter.consumeAll().size)
    }

    @Test
    fun `resetNoLinkWarning allows notifyNoLinkOnce to publish again`() {
        val alerts = createAlerts()

        alerts.notifyNoLinkOnce("no link")
        AppWarningCenter.clear()
        alerts.resetNoLinkWarning()
        alerts.notifyNoLinkOnce("no link again")

        assertTrue(AppWarningCenter.contains("no link again"))
    }
}
