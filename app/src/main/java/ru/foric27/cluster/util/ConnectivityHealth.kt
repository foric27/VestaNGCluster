package ru.foric27.cluster.util

internal data class RuntimeHealthSnapshot(
    val streamActive: Boolean,
    val startInProgress: Boolean,
    val senderReady: Boolean,
    val displayReady: Boolean,
    val recentVideoTraffic: Boolean,
    val routeReady: Boolean,
)

internal object ConnectivityHealth {

    fun isWakeStreamHealthy(snapshot: RuntimeHealthSnapshot): Boolean {
        return snapshot.streamActive &&
            !snapshot.startInProgress &&
            snapshot.senderReady &&
            snapshot.displayReady &&
            snapshot.recentVideoTraffic &&
            snapshot.routeReady
    }

    fun requiresWakeFullRecovery(snapshot: RuntimeHealthSnapshot): Boolean {
        // После wake проверка до peer может кратковременно не успевать, хотя route уже живой
        // и видео продолжает уходить. Не эскалируем такой случай сразу в full recovery.
        if (hasActiveWakeTraffic(snapshot)) {
            return !snapshot.displayReady
        }
        return !snapshot.streamActive ||
            snapshot.startInProgress ||
            !snapshot.senderReady ||
            !snapshot.displayReady ||
            !snapshot.routeReady
    }

    fun describeWakeSnapshot(snapshot: RuntimeHealthSnapshot): String {
        return buildString {
            append("streamActive=").append(snapshot.streamActive)
            append(", startInProgress=").append(snapshot.startInProgress)
            append(", senderReady=").append(snapshot.senderReady)
            append(", displayReady=").append(snapshot.displayReady)
            append(", recentVideoTraffic=").append(snapshot.recentVideoTraffic)
            append(", routeReady=").append(snapshot.routeReady)
        }
    }

    fun describeWakeDecision(snapshot: RuntimeHealthSnapshot): String {
        val streamHealthy = isWakeStreamHealthy(snapshot)
        val fullRecovery = requiresWakeFullRecovery(snapshot)
        val reason = when {
            streamHealthy -> "healthy"
            hasActiveWakeTraffic(snapshot) && !snapshot.displayReady -> "display_missing_with_live_traffic"
            hasActiveWakeTraffic(snapshot) -> "live_traffic_keeps_recovery_soft"
            !snapshot.streamActive -> "stream_inactive"
            snapshot.startInProgress -> "startup_in_progress"
            !snapshot.senderReady -> "sender_missing"
            !snapshot.displayReady -> "display_missing"
            !snapshot.routeReady -> "route_not_ready"
            !snapshot.recentVideoTraffic -> "recent_video_missing"
            else -> "unknown"
        }
        return "streamHealthy=$streamHealthy, requiresFullRecovery=$fullRecovery, reason=$reason"
    }

    private fun hasActiveWakeTraffic(snapshot: RuntimeHealthSnapshot): Boolean {
        return snapshot.streamActive &&
            !snapshot.startInProgress &&
            snapshot.senderReady &&
            snapshot.routeReady &&
            snapshot.recentVideoTraffic
    }

    fun isWatchdogConnectionHealthy(
        recentVideoTraffic: Boolean,
        routeReady: Boolean,
        udpProbeOk: Boolean,
    ): Boolean {
        if (recentVideoTraffic) return true
        if (!routeReady) return false
        return udpProbeOk
    }
}
