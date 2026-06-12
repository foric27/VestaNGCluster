package ru.foric27.cluster.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpServiceRestartControllerTest {

    private fun createController(
        onRestart: (String?) -> Unit = {},
        onScheduled: (Long) -> Unit = {},
    ) = UdpServiceRestartController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        tag = "test",
        onAttemptRestart = onRestart,
        notifyRestartScheduled = onScheduled,
    )

    @Test
    fun `initial backoff is RESTART_BACKOFF_START_MS`() {
        val ctrl = createController()
        assertEquals(700L, ctrl.currentBackoffMs())
    }

    @Test
    fun `increaseBackoff doubles the value`() {
        val ctrl = createController()
        ctrl.increaseBackoff()
        assertEquals(1400L, ctrl.currentBackoffMs())
    }

    @Test
    fun `increaseBackoff doubles again`() {
        val ctrl = createController()
        ctrl.increaseBackoff()
        ctrl.increaseBackoff()
        assertEquals(2800L, ctrl.currentBackoffMs())
    }

    @Test
    fun `increaseBackoff caps at RESTART_BACKOFF_MAX_MS`() {
        val ctrl = createController()
        repeat(10) { ctrl.increaseBackoff() }
        assertEquals(15000L, ctrl.currentBackoffMs())
    }

    @Test
    fun `increaseBackoff with min respects minimum`() {
        val ctrl = createController()
        val result = ctrl.increaseBackoff(10000)
        assertEquals(10000L, result)
        assertEquals(10000L, ctrl.currentBackoffMs())
    }

    @Test
    fun `ensureMinBackoff raises to minimum`() {
        val ctrl = createController()
        val result = ctrl.ensureMinBackoff(5000)
        assertEquals(5000L, result)
        assertEquals(5000L, ctrl.currentBackoffMs())
    }

    @Test
    fun `ensureMinBackoff does not lower`() {
        val ctrl = createController()
        ctrl.setBackoff(8000)
        ctrl.ensureMinBackoff(5000)
        assertEquals(8000L, ctrl.currentBackoffMs())
    }

    @Test
    fun `resetBackoff restores initial value`() {
        val ctrl = createController()
        ctrl.increaseBackoff()
        ctrl.increaseBackoff()
        ctrl.resetBackoff()
        assertEquals(700L, ctrl.currentBackoffMs())
    }

    @Test
    fun `setBackoff sets custom value`() {
        val ctrl = createController()
        ctrl.setBackoff(12345)
        assertEquals(12345L, ctrl.currentBackoffMs())
    }

    @Test
    fun `cancel does not throw when no job`() {
        val ctrl = createController()
        ctrl.cancel()
    }

    @Test
    fun `schedule notifies with backoff delay`() {
        var scheduledDelay: Long? = null
        val ctrl = createController(onScheduled = { scheduledDelay = it })
        ctrl.setBackoff(3000)
        ctrl.schedule("test_reason", null)
        assertEquals(3000L, scheduledDelay)
    }

    @Test
    fun `prepareImmediateRecovery calls notifyUser and schedule`() {
        var userMsg: String? = null
        var scheduledDelay: Long? = null
        val ctrl = createController(
            onScheduled = { scheduledDelay = it },
        )
        ctrl.prepareImmediateRecovery(
            reason = "test",
            minBackoffMs = 1000,
            logPipelineSnapshot = {},
            userMessage = "recovery msg",
            notifyUser = { userMsg = it },
            beforeSchedule = {},
        )
        assertEquals("recovery msg", userMsg)
        assertTrue(scheduledDelay!! >= 1000)
    }
}
