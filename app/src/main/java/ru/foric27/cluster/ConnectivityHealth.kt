package ru.foric27.cluster

internal data class PeerCheckResult(
    val attempted: Boolean,
    val ok: Boolean,
) {
    val failed: Boolean
        get() = attempted && !ok
}

internal data class RuntimeHealthSnapshot(
    val streamActive: Boolean,
    val startInProgress: Boolean,
    val senderReady: Boolean,
    val displayReady: Boolean,
    val recentVideoTraffic: Boolean,
    val routeReady: Boolean,
    val peerCheck: PeerCheckResult,
)

internal object ConnectivityHealth {

    fun isWakeStreamHealthy(snapshot: RuntimeHealthSnapshot): Boolean {
        return snapshot.streamActive &&
            !snapshot.startInProgress &&
            snapshot.senderReady &&
            snapshot.displayReady &&
            snapshot.recentVideoTraffic &&
            snapshot.routeReady &&
            !snapshot.peerCheck.failed
    }

    fun requiresWakeFullRecovery(snapshot: RuntimeHealthSnapshot): Boolean {
        return !snapshot.streamActive ||
            snapshot.startInProgress ||
            !snapshot.senderReady ||
            !snapshot.displayReady ||
            !snapshot.routeReady ||
            snapshot.peerCheck.failed
    }

    fun isWatchdogConnectionHealthy(
        recentVideoTraffic: Boolean,
        routeReady: Boolean,
        peerCheck: PeerCheckResult,
        udpProbeOk: Boolean,
    ): Boolean {
        if (recentVideoTraffic) return true
        if (!routeReady) return false
        return if (peerCheck.attempted) peerCheck.ok else udpProbeOk
    }
}
