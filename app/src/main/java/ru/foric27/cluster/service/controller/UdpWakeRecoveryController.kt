package ru.foric27.cluster.service.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

import android.os.SystemClock
import timber.log.Timber

/**
 * Контроллер восстановления стрима после sleep/wake.
 *
 * Регистрирует receiver экранного события, останавливает стрим при screen off
 * и перезапускает при пробуждении.
 */
/**
 * Контроллер восстановления стрима после sleep/wake.
 *
 * Регистрирует broadcast receiver для событий экрана (SCREEN_OFF, SCREEN_ON,
 * USER_PRESENT) и управляет остановкой/перезапуском стрима при сне устройства.
 */
internal class UdpWakeRecoveryController(
    private val context: Context,
    private val registerLocalReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiverBestEffort: (BroadcastReceiver?, String) -> Unit,
    private val stopStream: () -> Unit,
    private val restartStream: () -> Unit,
) {

    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiverRegistered = false
    private var screenSleepStartedAtMs = 0L

    /**
     * Регистрирует receiver для отслеживания событий экрана.
     *
     * Дедуплицирует вызовы через [screenStateReceiverRegistered].
     */
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
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction("android.intent.action.DREAMING_STOPPED")
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

    /**
     * Снимает receiver экранных событий и сбрасывает состояние сна.
     */
    fun unregister() {
        if (!screenStateReceiverRegistered) return
        unregisterReceiverBestEffort(screenStateReceiver, "экранных событий")
        screenStateReceiver = null
        screenStateReceiverRegistered = false
        clearPendingState()
    }

    /**
     * Сбрасывает метку времени начала сна.
     *
     * Вызывается при unregister или после обработки wake-события.
     */
    fun clearPendingState() {
        screenSleepStartedAtMs = 0L
    }

    /**
     * Обрабатывает выключение экрана: останавливает стрима.
     */
    private fun handleScreenOff() {
        screenSleepStartedAtMs = SystemClock.elapsedRealtime()
        stopStream()
        Timber.tag(TAG).i("Экран выключен; сервис остановлен до явного запуска пользователем")
    }

    /**
     * Обрабатывает событие пробуждения устройства: перезапускает стрима.
     *
     * @param action строковое представление intent action (для логов)
     */
    private fun handleWakeEvent(action: String) {
        val sleptAt = screenSleepStartedAtMs
        if (sleptAt == 0L) return

        val now = SystemClock.elapsedRealtime()
        Timber.tag(TAG).i("Устройство вышло из сна: action=$action, slept=${now - sleptAt}ms")
        clearPendingState()
        restartStream()
    }

    private companion object {
        private const val TAG = "UdpWakeRecoveryCtrl"
    }
}
