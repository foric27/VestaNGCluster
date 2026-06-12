package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.service.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpStartupProbeCoordinatorTest {

    @Test
    fun `handleRoutePreparationNotReady sets startInProgress to false`() {
        var startInProgress: Boolean? = null
        val coordinator = createCoordinator(
            setStartInProgress = { startInProgress = it },
        )

        coordinator.handleRoutePreparationNotReady("192.168.40.2", 2500L)

        assertEquals(false, startInProgress)
    }

    @Test
    fun `handleRoutePreparationNotReady schedules restart with net_wait reason`() {
        var scheduledReason: String? = null
        var scheduledCause: Throwable? = null
        val coordinator = createCoordinator(
            scheduleRestart = { reason, cause ->
                scheduledReason = reason
                scheduledCause = cause
            },
        )

        coordinator.handleRoutePreparationNotReady("192.168.40.2", 2500L)

        assertEquals("net_wait", scheduledReason)
        assertEquals(null, scheduledCause)
    }

    @Test
    fun `handleRouteNotReady closes sender quietly`() {
        var closedSender: UdpSender? = null
        val sender = UdpSender("127.0.0.1", 5004, null)
        val coordinator = createCoordinator(
            closeSenderQuietly = { closedSender = it },
        )

        try {
            coordinator.handleRouteNotReady(sender, "192.168.40.2", 2500L)
            assertTrue(closedSender === sender)
        } finally {
            sender.close()
        }
    }

    @Test
    fun `handleRouteNotReady clears sender if current`() {
        var clearedSender: UdpSender? = null
        val sender = UdpSender("127.0.0.1", 5004, null)
        val coordinator = createCoordinator(
            clearSenderIfCurrent = { clearedSender = it },
        )

        try {
            coordinator.handleRouteNotReady(sender, "192.168.40.2", 2500L)
            assertTrue(clearedSender === sender)
        } finally {
            sender.close()
        }
    }

    private fun createCoordinator(
        closeSenderQuietly: (UdpSender) -> Unit = {},
        clearSenderIfCurrent: (UdpSender) -> Unit = {},
        setStartInProgress: (Boolean) -> Unit = {},
        increaseRestartBackoff: (Long) -> Long = { it },
        logPipelineSnapshot: (String) -> Unit = {},
        notifyNoRoute: (String, Long) -> Unit = { _, _ -> },
        scheduleRestart: (String, Throwable?) -> Unit = { _, _ -> },
    ) = UdpStartupProbeCoordinator(
        tag = "UdpStartupProbeCoordinatorTest",
        routeWaitStepMs = 100L,
        closeSenderQuietly = closeSenderQuietly,
        clearSenderIfCurrent = clearSenderIfCurrent,
        setStartInProgress = setStartInProgress,
        increaseRestartBackoff = increaseRestartBackoff,
        logPipelineSnapshot = logPipelineSnapshot,
        notifyNoRoute = notifyNoRoute,
        scheduleRestart = scheduleRestart,
    )
}
