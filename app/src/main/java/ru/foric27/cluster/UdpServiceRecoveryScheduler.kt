package ru.foric27.cluster

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.util.Log

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
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            Log.w(tag, "Запланировано восстановление сервиса через ${delayMs}мс, reason=$reason, launchUi=$launchUi")
            onScheduled((delayMs / 1000L).toInt())
        } catch (t: Throwable) {
            Log.w(tag, "Не удалось запланировать восстановление сервиса, reason=$reason, launchUi=$launchUi", t)
            runCatching { onFallbackStart() }
                .onFailure { restartError ->
                    Log.e(tag, "Fallback-запуск сервиса тоже не удался", restartError)
                }
        }
    }

    fun cancel() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(reason = "", launchUi = false))
            alarmManager.cancel(buildPendingIntent(reason = "", launchUi = true))
        } catch (t: Throwable) {
            Log.w(tag, "Не удалось снять pending alarm восстановления", t)
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
