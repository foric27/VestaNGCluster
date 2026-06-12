package ru.foric27.cluster.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityHealthTest {

    @Test
    fun `wake stream is healthy when traffic and route are healthy`() {
        val snapshot = RuntimeHealthSnapshot(
            streamActive = true,
            startInProgress = false,
            senderReady = true,
            displayReady = true,
            recentVideoTraffic = true,
            routeReady = true,
        )

        assertTrue(ConnectivityHealth.isWakeStreamHealthy(snapshot))
        assertFalse(ConnectivityHealth.requiresWakeFullRecovery(snapshot))
    }

    @Test
    fun `watchdog connection stays healthy with successful udp probe`() {
        assertTrue(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                udpProbeOk = true,
            ),
        )
    }

    @Test
    fun `watchdog connection is unhealthy when udp probe fails`() {
        assertFalse(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                udpProbeOk = false,
            ),
        )
    }
}
