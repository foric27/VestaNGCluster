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
    fun `watchdog connection stays healthy with successful udp probe when ping unavailable`() {
        assertTrue(
            ConnectivityHealth.isWatchdogConnectionHealthy(
                recentVideoTraffic = false,
                routeReady = true,
                peerCheck = PeerCheckResult(attempted = false, ok = false),
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
