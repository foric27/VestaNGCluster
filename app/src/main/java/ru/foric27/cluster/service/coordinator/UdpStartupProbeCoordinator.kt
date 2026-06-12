package ru.foric27.cluster.service.coordinator
import ru.foric27.cluster.service.*

import android.os.SystemClock
import timber.log.Timber

/**
 * Координатор проверки готовности UDP-канала перед запуском pipeline.
 *
 * Ждёт ответа probe-пакета с таймаутом, планирует backoff-рестарт
 * при неудаче, логирует прогресс ожидания маршрута.
 */
/**
 * Координатор проверки готовности UDP перед запуском pipeline.
 *
 * Выполняет пробную отправку UDP-пакетов и ожидает успешного результата
 * в течение заданного таймаута. При неудаче планирует backoff-перезапуск.
 */
internal class UdpStartupProbeCoordinator(
    private val tag: String,
    private val routeWaitStepMs: Long,
    private val closeSenderQuietly: (UdpSender) -> Unit,
    private val clearSenderIfCurrent: (UdpSender) -> Unit,
    private val setStartInProgress: (Boolean) -> Unit,
    private val increaseRestartBackoff: (Long) -> Long,
    private val logPipelineSnapshot: (String) -> Unit,
    private val notifyNoRoute: (String, Long) -> Unit,
    private val scheduleRestart: (String, Throwable?) -> Unit,
) {

    /**
     * Ожидает готовности UDP-отправки через пробные пакеты.
     *
     * Повторяет [UdpSender.probe] с интервалом [routeWaitStepMs] до
     * успеха или исчерпания [timeoutMs].
     *
     * @param sender UDP sender для проверки
     * @param dstHost целевой хост (для логов)
     * @param timeoutMs максимальное время ожидания в миллисекундах
     * @return true если UDP готов к отправке, false при таймауте или закрытии sender
     */
    fun waitForUdpReady(
        sender: UdpSender,
        dstHost: String,
        timeoutMs: Long,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var lastLoggedWaitMs = 0L

        while (true) {
            if (sender.isClosed()) return false

            val probeOk = try {
                sender.probe()
            } catch (t: Throwable) {
                Timber.tag(tag).w(t, "Исключение при проверке UDP")
                false
            }

            if (probeOk) {
                return true
            }

            val waitedMs = SystemClock.elapsedRealtime() - startedAt
            if (waitedMs >= timeoutMs) {
                return false
            }

            if (waitedMs - lastLoggedWaitMs >= 1_000L) {
                lastLoggedWaitMs = waitedMs
                Timber.tag(tag).i("Ожидание готовности UDP: dst=$dstHost probeOk=$probeOk waited=${waitedMs}ms")
            }

            try {
                Thread.sleep(routeWaitStepMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    /**
     * Обрабатывает ситуацию, когда UDP probe не прошёл в течение таймаута.
     *
     * Закрывает sender, увеличивает backoff и планирует перезапуск.
     *
     * @param localSender UDP sender для закрытия
     * @param hostValue целевой хост (для логов и уведомлений)
     * @param noRouteRestartBackoffMinMs минимальный backoff при отсутствии маршрута
     */
    fun handleRouteNotReady(
        localSender: UdpSender,
        hostValue: String,
        noRouteRestartBackoffMinMs: Long,
    ) {
        closeSenderQuietly(localSender)
        clearSenderIfCurrent(localSender)
        handleRoutePreparationNotReady(hostValue, noRouteRestartBackoffMinMs)
    }

    /**
     * Обрабатывает ситуацию, когда сетевая подготовка не завершена.
     *
     * Устанавливает startInProgress=false, увеличивает backoff и планирует перезапуск.
     *
     * @param hostValue целевой хост (для логов и уведомлений)
     * @param noRouteRestartBackoffMinMs минимальный backoff при отсутствии маршрута
     */
    fun handleRoutePreparationNotReady(
        hostValue: String,
        noRouteRestartBackoffMinMs: Long,
    ) {
        setStartInProgress(false)
        val backoffMs = increaseRestartBackoff(noRouteRestartBackoffMinMs)
        logPipelineSnapshot("Маршрут не готов для $hostValue")
        Timber.tag(tag).w("Маршрут до $hostValue не готов; повторю позже. backoff=${backoffMs}ms")
        notifyNoRoute(hostValue, backoffMs)
        scheduleRestart("net_wait", null)
    }
}
