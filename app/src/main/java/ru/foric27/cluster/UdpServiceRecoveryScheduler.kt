package ru.foric27.cluster

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

internal class UdpServiceRecoveryScheduler(
    private val context: Context,
    private val tag: String,
    private val requestCode: Int,
    private val onScheduled: (delaySeconds: Int) -> Unit,
    private val onFallbackStart: () -> Unit,
) {

    fun schedule(reason: String, delayMs: Long, userReason: String = reason, launchUi: Boolean = false) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = buildPendingIntent(reason = userReason, launchUi = launchUi)
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Timber.tag(tag).w("Запланировано восстановление сервиса через ${delayMs}мс, reason=$reason, launchUi=$launchUi")
            onScheduled((delayMs / 1000L).toInt())
        } catch (t: Throwable) {
            Timber.tag(tag).w(t, "Не удалось запланировать восстановление сервиса, reason=$reason, launchUi=$launchUi")
            runCatching { onFallbackStart() }
                .onFailure { restartError ->
                    Timber.tag(tag).e(restartError, "Fallback-запуск сервиса тоже не удался")
                }
        }
    }

    fun cancel() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(reason = "", launchUi = false))
            alarmManager.cancel(buildPendingIntent(reason = "", launchUi = true))
        } catch (t: Throwable) {
            Timber.tag(tag).w(t, "Не удалось снять pending alarm восстановления")
        }
    }

    private fun buildPendingIntent(reason: String, launchUi: Boolean): PendingIntent {
        return AppRecoveryReceiver.createPendingIntent(
            context = context,
            requestCode = requestCode,
            reason = reason,
            launchUi = launchUi,
        )
    }
}
