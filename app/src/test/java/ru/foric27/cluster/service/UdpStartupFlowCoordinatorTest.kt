package ru.foric27.cluster.service
import ru.foric27.cluster.service.coordinator.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpStartupFlowCoordinatorTest {

    @Test
    fun `route preparation failure does not create sender or probe udp`() {
        var createSenderCalled = false
        var waitForUdpReadyCalled = false
        var handleRoutePreparationNotReadyCall: Pair<String, Long>? = null
        var onReadyPipelineCalled = false

        val coordinator = createCoordinator(
            createSender = { _, _, _ ->
                createSenderCalled = true
                throw AssertionError("UdpSender не должен создаваться без готового маршрута")
            },
            waitForUdpReady = { _, _, _ ->
                waitForUdpReadyCalled = true
                true
            },
            handleRoutePreparationNotReady = { host, backoff ->
                handleRoutePreparationNotReadyCall = host to backoff
            },
        )

        coordinator.start(
            hostValue = "192.168.40.2",
            port = 5004,
            bindIp = "192.168.40.1",
            routeReady = false,
            routeWaitTimeoutMs = 1_000L,
            noRouteRestartBackoffMinMs = 2_500L,
            onReadyPipeline = { onReadyPipelineCalled = true },
        )

        assertFalse(createSenderCalled)
        assertFalse(waitForUdpReadyCalled)
        assertFalse(onReadyPipelineCalled)
        assertEquals("192.168.40.2" to 2_500L, handleRoutePreparationNotReadyCall)
    }

    @Test
    fun `route ready creates sender probes udp and starts pipeline`() {
        val sender = UdpSender("127.0.0.1", 5004, null)
        var createSenderCalled = false
        var waitForUdpReadyCalled = false
        var assignedSender: UdpSender? = null
        var startInProgress: Boolean? = null
        var onReadySender: UdpSender? = null

        val coordinator = createCoordinator(
            createSender = { host, port, bindIp ->
                createSenderCalled = true
                assertEquals("127.0.0.1", host)
                assertEquals(5004, port)
                assertEquals(null, bindIp)
                sender
            },
            assignSender = { assignedSender = it },
            setStartInProgress = { startInProgress = it },
            waitForUdpReady = { probeSender, host, timeoutMs ->
                waitForUdpReadyCalled = true
                assertTrue(probeSender === sender)
                assertEquals("127.0.0.1", host)
                assertEquals(1_000L, timeoutMs)
                true
            },
        )

        try {
            coordinator.start(
                hostValue = "127.0.0.1",
                port = 5004,
                bindIp = null,
                routeReady = true,
                routeWaitTimeoutMs = 1_000L,
                noRouteRestartBackoffMinMs = 2_500L,
                onReadyPipeline = { onReadySender = it },
            )

            assertTrue(createSenderCalled)
            assertTrue(waitForUdpReadyCalled)
            assertTrue(assignedSender === sender)
            assertEquals(true, startInProgress)
            assertTrue(onReadySender === sender)
        } finally {
            sender.close()
        }
    }

    private fun createCoordinator(
        createSender: (String, Int, String?) -> UdpSender = { host, port, bindIp -> UdpSender(host, port, bindIp) },
        assignSender: (UdpSender?) -> Unit = {},
        setStartInProgress: (Boolean) -> Unit = {},
        scheduleRestart: (String, Throwable?) -> Unit = { _, _ -> },
        launchUdpProbe: (() -> Unit) -> Unit = { it() },
        postToMain: (() -> Unit) -> Unit = { it() },
        waitForUdpReady: (UdpSender, String, Long) -> Boolean = { _, _, _ -> true },
        handleRouteNotReady: (UdpSender, String, Long) -> Unit = { _, _, _ -> },
        handleRoutePreparationNotReady: (String, Long) -> Unit = { _, _ -> },
    ): UdpStartupFlowCoordinator {
        return UdpStartupFlowCoordinator(
            tag = "UdpStartupFlowCoordinatorTest",
            createSender = createSender,
            assignSender = assignSender,
            setStartInProgress = setStartInProgress,
            scheduleRestart = scheduleRestart,
            launchUdpProbe = launchUdpProbe,
            postToMain = postToMain,
            waitForUdpReady = waitForUdpReady,
            handleRouteNotReady = handleRouteNotReady,
            handleRoutePreparationNotReady = handleRoutePreparationNotReady,
        )
    }
}
