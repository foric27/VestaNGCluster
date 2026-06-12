package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.service.*

import timber.log.Timber

/**
 * Координатор startup-потока: создание UDP sender и запуск probe.
 *
 * Создаёт [UdpSender], назначает его, запускает UDP probe в фоне
 * и при успехе вызывает callback запуска pipeline.
 */
/**
 * Координатор стартового потока UDP streaming.
 *
 * Управляет созданием [UdpSender], выполнением UDP probe и передачей
 * управления в [UdpPipelineStartCoordinator] при успешной готовности.
 */
internal class UdpStartupFlowCoordinator(
    private val tag: String,
    private val createSender: (String, Int, String?) -> UdpSender,
    private val assignSender: (UdpSender?) -> Unit,
    private val setStartInProgress: (Boolean) -> Unit,
    private val scheduleRestart: (String, Throwable?) -> Unit,
    private val launchUdpProbe: (() -> Unit) -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
    private val waitForUdpReady: (UdpSender, String, Long) -> Boolean,
    private val handleRouteNotReady: (UdpSender, String, Long) -> Unit,
    private val handleRoutePreparationNotReady: (String, Long) -> Unit,
) {

    /**
     * Запускает стартовый поток: создание sender, UDP probe и запуск pipeline.
     *
     * Если маршрут не готов — сразу вызывает [handleRoutePreparationNotReady].
     * При успешном probe передаёт управление в [onReadyPipeline] на main thread.
     *
     * @param hostValue целевой хост
     * @param port целевой порт
     * @param bindIp локальный IP для привязки или null
     * @param routeReady готов ли маршрут (результат сетевой подготовки)
     * @param routeWaitTimeoutMs таймаут ожидания UDP probe
     * @param noRouteRestartBackoffMinMs минимальный backoff при отсутствии маршрута
     * @param onReadyPipeline callback для запуска готового pipeline
     */
    fun start(
        hostValue: String,
        port: Int,
        bindIp: String?,
        routeReady: Boolean,
        routeWaitTimeoutMs: Long,
        noRouteRestartBackoffMinMs: Long,
        onReadyPipeline: (UdpSender) -> Unit,
    ) {
        if (!routeReady) {
            handleRoutePreparationNotReady(hostValue, noRouteRestartBackoffMinMs)
            return
        }

        val localSender = try {
            createSender(hostValue, port, bindIp)
        } catch (t: Throwable) {
            setStartInProgress(false)
            Timber.tag(tag).e(t, "Не удалось создать UdpSender")
            scheduleRestart("udp_sender_init", t)
            return
        }

        assignSender(localSender)
        setStartInProgress(true)

        launchUdpProbe {
            val udpReady = waitForUdpReady(localSender, hostValue, routeWaitTimeoutMs)
            if (!udpReady) {
                postToMain {
                    handleRouteNotReady(localSender, hostValue, noRouteRestartBackoffMinMs)
                }
                return@launchUdpProbe
            }

            postToMain {
                onReadyPipeline(localSender)
            }
        }
    }
}
