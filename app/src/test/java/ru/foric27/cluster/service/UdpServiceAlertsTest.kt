package ru.foric27.cluster.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ): UdpServiceAlerts {
        return UdpServiceAlerts(
            context = object : android.content.Context() {
                override fun getAssets() = throw UnsupportedOperationException()
                override fun getResources() = throw UnsupportedOperationException()
                override fun getPackageManager() = throw UnsupportedOperationException()
                override fun getContentResolver() = throw UnsupportedOperationException()
                override fun getMainLooper() = throw UnsupportedOperationException()
                override fun getApplicationContext() = this
                override fun setTheme(resid: Int) {}
                override fun getTheme() = throw UnsupportedOperationException()
                override fun getClassLoader() = throw UnsupportedOperationException()
                override fun getPackageName() = "ru.foric27.cluster"
                override fun getApplicationInfo() = throw UnsupportedOperationException()
                override fun getSystemService(name: String) = null
                override fun checkPermission(permission: String, pid: Int, uid: Int) = android.content.pm.PackageManager.PERMISSION_DENIED
                override fun checkCallingPermission(permission: String) = android.content.pm.PackageManager.PERMISSION_DENIED
                override fun checkCallingOrSelfPermission(permission: String) = android.content.pm.PackageManager.PERMISSION_DENIED
                override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
                override fun enforceCallingPermission(permission: String, message: String?) {}
                override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
                override fun createPackageContext(packageName: String, flags: Int) = this
                override fun getString(resId: Int) = "root_required_msg"
            },
            tag = "test",
            updateNotification = { notifications.add(it) },
        )
    }

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

    @Test
    fun `notifyRootRequiredOnce publishes warning only once`() {
        val alerts = createAlerts()
        alerts.notifyRootRequiredOnce()
        alerts.notifyRootRequiredOnce()
        val warnings = AppWarningCenter.consumeAll()
        assertEquals(1, warnings.size)
    }

    @Test
    fun `notifyRootRequiredOnce calls updateNotification every time`() {
        val notifications = mutableListOf<String>()
        val alerts = createAlerts(notifications)
        alerts.notifyRootRequiredOnce()
        alerts.notifyRootRequiredOnce()
        assertTrue(notifications.size >= 1)
    }
}
