package ru.foric27.cluster.service.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpStartupProbeCoordinatorTest {

    private fun createCoordinator(
        closeSender: MutableList<Any> = mutableListOf(),
        clearSender: MutableList<Any> = mutableListOf(),
        startInProgressValues: MutableList<Boolean> = mutableListOf(),
        backoffMinValues: MutableList<Long> = mutableListOf(),
        restartReasons: MutableList<String> = mutableListOf(),
        snapshotMessages: MutableList<String> = mutableListOf(),
        noRouteHosts: MutableList<String> = mutableListOf(),
    ) = UdpStartupProbeCoordinator(
        tag = "test",
        routeWaitStepMs = 10,
        closeSenderQuietly = { closeSender.add(it) },
        clearSenderIfCurrent = { clearSender.add(it) },
        setStartInProgress = { startInProgressValues.add(it) },
        increaseRestartBackoff = { minBackoff ->
            backoffMinValues.add(minBackoff)
            minBackoff
        },
        logPipelineSnapshot = { snapshotMessages.add(it) },
        notifyNoRoute = { host, _ -> noRouteHosts.add(host) },
        scheduleRestart = { reason, _ -> restartReasons.add(reason) },
    )

    @Test
    fun `handleRoutePreparationNotReady sets startInProgress false`() {
        val startInProgressValues = mutableListOf<Boolean>()
        val coordinator = createCoordinator(startInProgressValues = startInProgressValues)
        coordinator.handleRoutePreparationNotReady("192.168.40.2", 1000)
        assertEquals(listOf(false), startInProgressValues)
    }

    @Test
    fun `handleRoutePreparationNotReady schedules restart with net_wait reason`() {
        val restartReasons = mutableListOf<String>()
        val coordinator = createCoordinator(restartReasons = restartReasons)
        coordinator.handleRoutePreparationNotReady("192.168.40.2", 1000)
        assertEquals(listOf("net_wait"), restartReasons)
    }

    @Test
    fun `handleRoutePreparationNotReady logs pipeline snapshot`() {
        val snapshotMessages = mutableListOf<String>()
        val coordinator = createCoordinator(snapshotMessages = snapshotMessages)
        coordinator.handleRoutePreparationNotReady("192.168.40.2", 1000)
        assertTrue(snapshotMessages.any { it.contains("192.168.40.2") })
    }

    @Test
    fun `handleRoutePreparationNotReady notifies no route`() {
        val noRouteHosts = mutableListOf<String>()
        val coordinator = createCoordinator(noRouteHosts = noRouteHosts)
        coordinator.handleRoutePreparationNotReady("192.168.40.2", 1000)
        assertEquals(listOf("192.168.40.2"), noRouteHosts)
    }

    @Test
    fun `handleRoutePreparationNotReady passes minBackoff to increaseRestartBackoff`() {
        val backoffMinValues = mutableListOf<Long>()
        val coordinator = createCoordinator(backoffMinValues = backoffMinValues)
        coordinator.handleRoutePreparationNotReady("192.168.40.2", 5000)
        assertEquals(listOf(5000L), backoffMinValues)
    }
}
