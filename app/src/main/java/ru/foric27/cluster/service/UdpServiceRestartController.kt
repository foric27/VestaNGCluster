package ru.foric27.cluster.service
import ru.foric27.cluster.config.*

import android.os.SystemClock
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Контроллер backoff-перезапусков сервиса.
 *
 * Управляет экспоненциальным backoff при повторных попытках запуска,
 * дедуплицирует запросы на перезапуск и планирует их через [CoroutineScope].
 */
internal class UdpServiceRestartController(
    private val scope: CoroutineScope,
    private val tag: String,
    private val onAttemptRestart: (String?) -> Unit,
    private val notifyRestartScheduled: (delayMs: Long) -> Unit,
) {

    private val restartScheduled = AtomicBoolean(false)
    private var restartJob: Job? = null

    @Volatile private var restartBackoffMs = RuntimeConfig.Service.RESTART_BACKOFF_START_MS
    @Volatile private var lastRestartRequestMs = 0L
    @Volatile private var lastCodecErrorRestartMs = 0L
    @Volatile private var pendingRestartReason: String? = null

    /**
     * Сбрасывает backoff до начального значения.
     *
     * Вызывается при успешном запуске стрима.
     */
    fun resetBackoff() {
        restartBackoffMs = RuntimeConfig.Service.RESTART_BACKOFF_START_MS
    }

    /**
     * Устанавливает текущее значение backoff вручную.
     *
     * @param value новое значение backoff в миллисекундах
     */
    fun setBackoff(value: Long) {
        restartBackoffMs = value
    }

    /**
     * Возвращает текущее значение backoff.
     *
     * @return текущий backoff в миллисекундах
     */
    fun currentBackoffMs(): Long = restartBackoffMs

    /**
     * Увеличивает backoff вдвое с учётом минимального порога и максимума.
     *
     * @param minBackoffMs минимально допустимое значение backoff
     * @return новое значение backoff в миллисекундах
     */
    fun increaseBackoff(minBackoffMs: Long = 0L): Long {
        restartBackoffMs = maxOf(
            minBackoffMs,
            minOf(RuntimeConfig.Service.RESTART_BACKOFF_MAX_MS, restartBackoffMs * 2),
        )
        return restartBackoffMs
    }

    /**
     * Гарантирует, что backoff не меньше указанного минимума.
     *
     * @param minBackoffMs минимальное значение backoff
     * @return актуальное значение backoff в миллисекундах
     */
    fun ensureMinBackoff(minBackoffMs: Long): Long {
        restartBackoffMs = maxOf(minBackoffMs, restartBackoffMs)
        return restartBackoffMs
    }

    /**
     * Планирует отложенный перезапуск сервиса с учётом debounce.
     *
     * Для ошибок кодека (["udp_error"]) используется отдельный debounce.
     * Остальные причины дедуплицируются через общий интервал.
     *
     * @param reason причина перезапуска (например, "udp_error", "net_wait")
     * @param cause исключение, вызвавшее перезапуск, или null
     */
    fun schedule(reason: String, cause: Throwable?) {
        val now = SystemClock.elapsedRealtime()
        if (reason == "udp_error") {
            if (now - lastCodecErrorRestartMs < RuntimeConfig.Service.CODEC_ERROR_RESTART_DEBOUNCE_MS) {
                return
            }
            lastCodecErrorRestartMs = now
        } else {
            if (now - lastRestartRequestMs < RuntimeConfig.Service.RESTART_REQUEST_DEBOUNCE_MS) {
                return
            }
            lastRestartRequestMs = now
        }

        if (!restartScheduled.compareAndSet(false, true)) {
            return
        }

        pendingRestartReason = reason
        val delayMs = restartBackoffMs
        Timber.tag(tag).w("Перезапуск запланирован через ${delayMs}мс, reason=$reason${cause?.let { " cause=$it" } ?: ""}")
        notifyRestartScheduled(delayMs)
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            attemptScheduledRestart()
        }
    }

    /**
     * Отменяет запланированный перезапуск и сбрасывает флаги.
     */
    fun cancel() {
        restartScheduled.set(false)
        pendingRestartReason = null
        restartJob?.cancel()
        restartJob = null
    }

    /**
     * Подготавливает и запускает немедленное восстановление с заданным минимальным backoff.
     *
     * Логирует снимок состояния, уведомляет пользователя и планирует перезапуск.
     *
     * @param reason причина восстановления
     * @param minBackoffMs минимальный backoff перед перезапуском
     * @param logPipelineSnapshot callback для логирования снимка состояния
     * @param userMessage сообщение для пользователя
     * @param notifyUser callback для публикации уведомления пользователю
     * @param beforeSchedule callback, выполняемый перед планированием перезапуска
     */
    fun prepareImmediateRecovery(
        reason: String,
        minBackoffMs: Long,
        logPipelineSnapshot: (String) -> Unit,
        userMessage: String,
        notifyUser: (String) -> Unit,
        beforeSchedule: () -> Unit,
    ) {
        val backoffMs = ensureMinBackoff(minBackoffMs)
        logPipelineSnapshot("Немедленное восстановление, reason=$reason")
        Timber.tag(tag).w("Немедленное восстановление стрима, reason=$reason, backoff=${backoffMs}ms")
        notifyUser(userMessage)
        cancel()
        lastRestartRequestMs = 0L
        beforeSchedule()
        schedule(reason, null)
    }

    private fun attemptScheduledRestart() {
        restartScheduled.set(false)
        val reason = pendingRestartReason
        pendingRestartReason = null
        onAttemptRestart(reason)
    }
}
