package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityHealthTest {

    @Test
    fun `wake stream is healthy when traffic route and peer are healthy`() {
        val snapshot = RuntimeHealthSnapshot(
            streamActive = true,
            startInProgress = false,
            senderReady = true,
            displayReady = true,
            recentVideoTraffic = true,
            routeReady = true,
            peerCheck = PeerCheckResult(attempted = true, ok = true),
        )

        assertTrue(ConnectivityHealth.isWakeStreamHealthy(snapshot))
        assertFalse(ConnectivityHealth.requiresWakeFullRecovery(snapshot))
    }

    @Test
    fun `wake requires full recovery when peer ping fails`() {
        val snapshot = RuntimeHealthSnapshot(
            streamActive = true,
            startInProgress = false,
            senderReady = true,
            displayReady = true,
            recentVideoTraffic = false,
            routeReady = true,
            peerCheck = PeerCheckResult(attempted = true, ok = false),
        )

        assertFalse(ConnectivityHealth.isWakeStreamHealthy(snapshot))
        assertTrue(ConnectivityHealth.requiresWakeFullRecovery(snapshot))
    }

    @Test
    fun `watchdog connection stays healthy with successful ping`() {
        assertTrue(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                peerCheck = PeerCheckResult(attempted = true, ok = true),
                udpProbeOk = false,
            ),
        )
    }

    @Test
    fun `watchdog connection is unhealthy when ping fails despite route`() {
        assertFalse(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                peerCheck = PeerCheckResult(attempted = true, ok = false),
                udpProbeOk = true,
            ),
        )
    }

    @Test
    fun `watchdog falls back to udp probe when ping is unavailable`() {
        assertTrue(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                peerCheck = PeerCheckResult(attempted = false, ok = false),
                udpProbeOk = true,
            ),
        )
    }
}
