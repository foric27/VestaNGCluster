package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.os.SystemClock
import timber.log.Timber

internal class UdpWakeRecoveryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val stopStream: () -> Unit,
) {

    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiverRegistered = false
    private var screenSleepStartedAtMs = 0L
    private var lastWakeRecoveryAtMs = 0L

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

    private companion object {
        private const val TAG = "UdpWakeRecoveryCtrl"
        private const val WAKE_RECOVERY_DEBOUNCE_MS = 2_000L
    }
}
