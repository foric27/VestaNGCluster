package ru.foric27.cluster.util

/**
 * Снимок состояния здоровья runtime-подсистем.
 *
 * Используется для принятия решений о восстановлении после wake.
 *
 * @property streamActive активен ли поток
 * @property startInProgress идёт ли запуск
 * @property senderReady готов ли UDP sender
 * @property displayReady готов ли VirtualDisplay
 * @property recentVideoTraffic был ли недавно видео-трафик
 * @property routeReady готов ли сетевой маршрут
 */
internal data class RuntimeHealthSnapshot(
    val streamActive: Boolean,
    val startInProgress: Boolean,
    val senderReady: Boolean,
    val displayReady: Boolean,
    val recentVideoTraffic: Boolean,
    val routeReady: Boolean,
)

/**
 * Проверка здоровья connectivity и принятие решений о восстановлении.
 */
internal object ConnectivityHealth {

    /**
     * Проверяет, здоров ли поток после wake.
     *
     * @param snapshot текущий снимок состояния
     * @return true, если все подсистемы в норме
     */
    fun isWakeStreamHealthy(snapshot: RuntimeHealthSnapshot): Boolean {
        return snapshot.streamActive &&
            !snapshot.startInProgress &&
            snapshot.senderReady &&
            snapshot.displayReady &&
            snapshot.recentVideoTraffic &&
            snapshot.routeReady
    }

    /**
     * Определяет, требуется ли полное восстановление после wake.
     *
     * @param snapshot текущий снимок состояния
     * @return true, если нужен полный перезапуск потока
     */
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

    /**
     * Возвращает текстовое описание снимка состояния.
     *
     * @param snapshot текущий снимок состояния
     * @return строка с перечислением флагов
     */
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

    /**
     * Возвращает текстовое описание решения о восстановлении.
     *
     * @param snapshot текущий снимок состояния
     * @return строка с решением и причиной
     */
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

    /**
     * Проверяет здоровье соединения для watchdog.
     *
     * @param recentVideoTraffic был ли недавно видео-трафик
     * @param routeReady готов ли маршрут
     * @param udpProbeOk успешен ли UDP probe
     * @return true, если соединение считается здоровым
     */
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
