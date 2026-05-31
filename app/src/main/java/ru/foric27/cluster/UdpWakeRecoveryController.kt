package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class UdpWakeRecoverySnapshot(
    val streamHealthy: Boolean,
    val requiresFullRecovery: Boolean,
    val recoveryBlocked: Boolean = false,
)

internal class UdpWakeRecoveryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val startDetachedWorker: (name: String, block: () -> Unit) -> Unit,
    private val postToMain: (block: () -> Unit) -> Unit,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val snapshotProvider: () -> UdpWakeRecoverySnapshot,
    private val logPipelineSnapshot: (String) -> Unit,
    private val forceOutputFrame: (String) -> Unit,
    private val relaunchTargetActivity: (String) -> Unit,
    private val requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,
    private val stopStream: () -> Unit,
    private val startStream: () -> Unit,
) {

    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiverRegistered = false
    private var screenSleepStartedAtMs = 0L
    private var lastWakeRecoveryAtMs = 0L
    private var pendingWakeAction: String? = null
    private var wakeRecoveryStage = 0
    @Volatile private var wakeRecoveryGeneration = 0L
    private var wakeRecoveryJob: Job? = null

    fun register() {
        if (screenStateReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> handleWakeEvent(intent.action.orEmpty())
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        try {
            registerLocalReceiver(receiver, filter)
            screenStateReceiver = receiver
            screenStateReceiverRegistered = true
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось зарегистрировать receiver экранных событий")
            screenStateReceiver = null
            screenStateReceiverRegistered = false
        }
    }

    fun unregister() {
        if (!screenStateReceiverRegistered) return
        unregisterReceiverBestEffort(screenStateReceiver, "экранных событий")
        screenStateReceiver = null
        screenStateReceiverRegistered = false
        clearPendingState()
    }

    fun clearPendingState() {
        screenSleepStartedAtMs = 0L
        pendingWakeAction = null
        wakeRecoveryStage = 0
        wakeRecoveryGeneration += 1L
        wakeRecoveryJob?.cancel()
        wakeRecoveryJob = null
    }

    private fun handleScreenOff() {
        screenSleepStartedAtMs = SystemClock.elapsedRealtime()
        stopStream()
        Timber.tag(TAG).i("Экран выключен; сервис остановлен до явного запуска пользователем")
    }

    private fun handleWakeEvent(action: String) {
        val sleptAt = screenSleepStartedAtMs
        if (sleptAt == 0L) return

        val now = SystemClock.elapsedRealtime()
        if ((now - lastWakeRecoveryAtMs) < WAKE_RECOVERY_DEBOUNCE_MS) {
            return
        }
        lastWakeRecoveryAtMs = now
        Timber.tag(TAG).i("Устройство вышло из сна: action=$action, slept=${now - sleptAt}ms")
        clearPendingState()
        Timber.tag(TAG).i("Автовосстановление после сна отключено: ожидаю явный запуск пользователя")
    }

    private fun launchWakeRecoveryVerification() {
        val action = pendingWakeAction ?: return
        val generation = wakeRecoveryGeneration
        val stage = wakeRecoveryStage
        startDetachedWorker("WakeRecoveryVerify") {
            val startedAtMs = SystemClock.elapsedRealtime()
            val snapshot = snapshotProvider()
            val durationMs = SystemClock.elapsedRealtime() - startedAtMs
            Timber.tag(TAG).i("Wake health-check завершён за ${durationMs}мс, action=$action, stage=$stage")
            postToMain {
                if (pendingWakeAction != action || wakeRecoveryGeneration != generation) {
                    Timber.tag(TAG).i("Игнорирую устаревший wake health-check, action=$action, stage=$stage")
                    return@postToMain
                }
                verifyWakeRecovery(action, snapshot)
            }
        }
    }

    private fun verifyWakeRecovery(action: String, snapshot: UdpWakeRecoverySnapshot) {
        if (snapshot.streamHealthy) {
            Timber.tag(TAG).i("После выхода из сна поток уже активен; лишний relaunch не нужен")
            pendingWakeAction = null
            wakeRecoveryStage = 0
            return
        }

        if (snapshot.recoveryBlocked) {
            Timber.tag(TAG).i("После выхода из сна восстановление отложено: сеть или root пока недоступны")
            clearPendingState()
            return
        }

        if (snapshot.requiresFullRecovery) {
            logPipelineSnapshot("Wake recovery требует полного восстановления")
            clearPendingState()
            requestImmediateRecovery(
                "wake_recovery",
                RESTART_BACKOFF_START_MS,
                context.getString(R.string.service_notification_wake_recovery),
            )
            return
        }

        if (wakeRecoveryStage == 0) {
            wakeRecoveryStage = 1
            forceOutputFrame("wake:$action")
            wakeRecoveryJob?.cancel()
            wakeRecoveryJob = scope.launch {
                delay(WAKE_FORCE_FRAME_SETTLE_DELAY_MS)
                launchWakeRecoveryVerification()
            }
            return
        }

        if (wakeRecoveryStage == 1) {
            wakeRecoveryStage = 2
            relaunchTargetActivity("wake:$action")
            wakeRecoveryJob?.cancel()
            wakeRecoveryJob = scope.launch {
                delay(WAKE_RELAUNCH_SETTLE_DELAY_MS)
                launchWakeRecoveryVerification()
            }
            return
        }

        if (!snapshot.requiresFullRecovery) {
            Timber.tag(TAG).i("Wake recovery завершён без полного рестарта: soft-actions исчерпаны, full recovery не требуется")
            clearPendingState()
            return
        }

        clearPendingState()
        requestImmediateRecovery(
            "wake_recovery",
            RESTART_BACKOFF_START_MS,
            context.getString(R.string.service_notification_wake_recovery_failed),
        )
    }

    private companion object {
        private const val TAG = "UdpWakeRecoveryCtrl"
        private const val WAKE_VERIFY_DELAY_MS = 700L
        private const val WAKE_FORCE_FRAME_SETTLE_DELAY_MS = 500L
        private const val WAKE_RELAUNCH_SETTLE_DELAY_MS = 1_200L
        private const val WAKE_RECOVERY_DEBOUNCE_MS = 2_000L
        private const val RESTART_BACKOFF_START_MS = 500L
    }
}
