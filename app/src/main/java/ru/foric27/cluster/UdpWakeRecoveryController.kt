package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.util.Log

internal data class UdpWakeRecoverySnapshot(
    val streamHealthy: Boolean,
    val requiresFullRecovery: Boolean,
)

internal class UdpWakeRecoveryController(
    private val context: Context,
    private val mainHandler: Handler,
    private val startDetachedWorker: (name: String, block: () -> Unit) -> Unit,
    private val postToMain: (block: () -> Unit) -> Unit,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val snapshotProvider: () -> UdpWakeRecoverySnapshot,
    private val logPipelineSnapshot: (String) -> Unit,
    private val forceOutputFrame: (String) -> Unit,
    private val relaunchTargetActivity: (String) -> Unit,
    private val requestImmediateRecovery: (reason: String, minBackoffMs: Long, userMessage: String) -> Unit,
) {

    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiverRegistered = false
    private var screenSleepStartedAtMs = 0L
    private var lastWakeRecoveryAtMs = 0L
    private var pendingWakeAction: String? = null
    private var wakeRecoveryStage = 0
    @Volatile private var wakeRecoveryGeneration = 0L

    private val wakeRecoveryVerifyRunnable = Runnable { launchWakeRecoveryVerification() }

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
            Log.w(TAG, "Не удалось зарегистрировать receiver экранных событий", t)
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
        mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
    }

    private fun handleScreenOff() {
        screenSleepStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Экран выключен; отслеживаю восстановление после выхода из сна")
    }

    private fun handleWakeEvent(action: String) {
        val sleptAt = screenSleepStartedAtMs
        if (sleptAt == 0L) return

        val now = SystemClock.elapsedRealtime()
        if ((now - lastWakeRecoveryAtMs) < WAKE_RECOVERY_DEBOUNCE_MS) {
            return
        }
        lastWakeRecoveryAtMs = now
        screenSleepStartedAtMs = 0L

        Log.i(TAG, "Устройство вышло из сна: action=$action, slept=${now - sleptAt}ms")
        pendingWakeAction = action
        wakeRecoveryStage = 0
        wakeRecoveryGeneration += 1L
        mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
        mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_VERIFY_DELAY_MS)
    }

    private fun launchWakeRecoveryVerification() {
        val action = pendingWakeAction ?: return
        val generation = wakeRecoveryGeneration
        val stage = wakeRecoveryStage
        startDetachedWorker("WakeRecoveryVerify") {
            val startedAtMs = SystemClock.elapsedRealtime()
            val snapshot = snapshotProvider()
            val durationMs = SystemClock.elapsedRealtime() - startedAtMs
            Log.i(TAG, "Wake health-check завершён за ${durationMs}мс, action=$action, stage=$stage")
            postToMain {
                if (pendingWakeAction != action || wakeRecoveryGeneration != generation) {
                    Log.i(TAG, "Игнорирую устаревший wake health-check, action=$action, stage=$stage")
                    return@postToMain
                }
                verifyWakeRecovery(action, snapshot)
            }
        }
    }

    private fun verifyWakeRecovery(action: String, snapshot: UdpWakeRecoverySnapshot) {
        if (snapshot.streamHealthy) {
            Log.i(TAG, "После выхода из сна поток уже активен; лишний relaunch не нужен")
            pendingWakeAction = null
            wakeRecoveryStage = 0
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
            mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
            mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_FORCE_FRAME_SETTLE_DELAY_MS)
            return
        }

        if (wakeRecoveryStage == 1) {
            wakeRecoveryStage = 2
            relaunchTargetActivity("wake:$action")
            mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
            mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_RELAUNCH_SETTLE_DELAY_MS)
            return
        }

        if (!snapshot.requiresFullRecovery) {
            Log.i(TAG, "Wake recovery завершён без полного рестарта: soft-actions исчерпаны, full recovery не требуется")
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
