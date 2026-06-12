package ru.foric27.cluster.service

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpServiceRestartControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createController(
        onAttemptRestart: (String?) -> Unit = {},
        notifyRestartScheduled: (Long) -> Unit = {},
    ) = UdpServiceRestartController(
        scope = testScope,
        tag = "UdpServiceRestartControllerTest",
        onAttemptRestart = onAttemptRestart,
        notifyRestartScheduled = notifyRestartScheduled,
    )

    @Test
    fun `currentBackoffMs initial value is RESTART_BACKOFF_START_MS`() {
        val controller = createController()
        assertEquals(700L, controller.currentBackoffMs())
    }

    @Test
    fun `increaseBackoff doubles the backoff`() {
        val controller = createController()
        assertEquals(1400L, controller.increaseBackoff())
    }

    @Test
    fun `increaseBackoff doubles again`() {
        val controller = createController()
        controller.increaseBackoff()
        assertEquals(2800L, controller.increaseBackoff())
    }

    @Test
    fun `increaseBackoff caps at RESTART_BACKOFF_MAX_MS`() {
        val controller = createController()
        repeat(5) { controller.increaseBackoff() }
        assertEquals(15000L, controller.increaseBackoff())
    }

    @Test
    fun `increaseBackoff with minBackoffMs uses max of min and doubled`() {
        val controller = createController()
        assertEquals(10000L, controller.increaseBackoff(10000L))
    }

    @Test
    fun `ensureMinBackoff respects min even above cap`() {
        val controller = createController()
        assertEquals(20000L, controller.ensureMinBackoff(20000L))
    }

    @Test
    fun `resetBackoff restores to RESTART_BACKOFF_START_MS`() {
        val controller = createController()
        controller.increaseBackoff()
        controller.increaseBackoff()
        controller.resetBackoff()
        assertEquals(700L, controller.currentBackoffMs())
    }

    @Test
    fun `setBackoff sets exact value`() {
        val controller = createController()
        controller.setBackoff(5000L)
        assertEquals(5000L, controller.currentBackoffMs())
    }

    @Test
    fun `cancel does not throw when no job scheduled`() {
        val controller = createController()
        controller.cancel()
    }

    @Test
    fun `schedule debounces when called within debounce window`() = runTest {
        var callCount = 0
        val controller = createController(
            notifyRestartScheduled = { callCount++ },
        )
        controller.schedule("test_reason", null)
        controller.schedule("test_reason", null)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, callCount)
    }

    @Test
    fun `cancel resets scheduled state`() {
        var callCount = 0
        val controller = createController(
            notifyRestartScheduled = { callCount++ },
        )
        controller.cancel()
        controller.cancel()
    }

    @Test
    fun `prepareImmediateRecovery calls notifyUser`() = runTest {
        var notifiedMessage: String? = null
        val controller = createController(
            notifyRestartScheduled = {},
        )

        controller.prepareImmediateRecovery(
            reason = "test",
            minBackoffMs = 5000L,
            logPipelineSnapshot = {},
            userMessage = "recovering",
            notifyUser = { notifiedMessage = it },
            beforeSchedule = {},
        )

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("recovering", notifiedMessage)
    }

    @Test
    fun `prepareImmediateRecovery calls beforeSchedule`() = runTest {
        var beforeScheduleCalled = false
        val controller = createController(
            notifyRestartScheduled = {},
        )

        controller.prepareImmediateRecovery(
            reason = "test",
            minBackoffMs = 5000L,
            logPipelineSnapshot = {},
            userMessage = "recovering",
            notifyUser = {},
            beforeSchedule = { beforeScheduleCalled = true },
        )

        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(beforeScheduleCalled)
    }
}
