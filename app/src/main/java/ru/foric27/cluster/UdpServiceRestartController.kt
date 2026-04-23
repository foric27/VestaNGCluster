package ru.foric27.cluster

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var pendingRestartReason: String? = null

    fun resetBackoff() {
        restartBackoffMs = RuntimeConfig.Service.RESTART_BACKOFF_START_MS
    }

    fun setBackoff(value: Long) {
        restartBackoffMs = value
    }

    fun currentBackoffMs(): Long = restartBackoffMs

    fun increaseBackoff(minBackoffMs: Long = 0L): Long {
        restartBackoffMs = maxOf(
            minBackoffMs,
            minOf(RuntimeConfig.Service.RESTART_BACKOFF_MAX_MS, restartBackoffMs * 2),
        )
        return restartBackoffMs
    }

    fun ensureMinBackoff(minBackoffMs: Long): Long {
        restartBackoffMs = maxOf(minBackoffMs, restartBackoffMs)
        return restartBackoffMs
    }

    fun schedule(reason: String, cause: Throwable?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartRequestMs < RuntimeConfig.Service.RESTART_REQUEST_DEBOUNCE_MS) {
            return
        }
        lastRestartRequestMs = now

        if (!restartScheduled.compareAndSet(false, true)) {
            return
        }

        pendingRestartReason = reason
        val delayMs = restartBackoffMs
        Log.w(tag, "Перезапуск запланирован через ${delayMs}мс, reason=$reason${cause?.let { " cause=$it" } ?: ""}")
        notifyRestartScheduled(delayMs)
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            attemptScheduledRestart()
        }
    }

    fun cancel() {
        restartScheduled.set(false)
        pendingRestartReason = null
        restartJob?.cancel()
        restartJob = null
    }

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
        Log.w(tag, "Немедленное восстановление стрима, reason=$reason, backoff=${backoffMs}ms")
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
