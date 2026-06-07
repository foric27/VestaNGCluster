package ru.foric27.cluster

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                Timber.tag(tag).w("SCHEDULE_EXACT_ALARM не выдано, пропускаю планирование восстановления")
                return
            }
            val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs
            val intent = Intent(context, AppRecoveryReceiver::class.java).apply {
                action = AppRecoveryReceiver.ACTION_RECOVER_APP
                setPackage(context.packageName)
                putExtra(AppRecoveryReceiver.EXTRA_REASON, userReason)
                putExtra(AppRecoveryReceiver.EXTRA_LAUNCH_UI, launchUi)
                putExtra(AppRecoveryReceiver.EXTRA_KEEP_IN_FOREGROUND, false)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
            alarmManager.cancel(makePendingIntent(reason = "", launchUi = false))
            alarmManager.cancel(makePendingIntent(reason = "", launchUi = true))
        } catch (t: Throwable) {
            Timber.tag(tag).w(t, "Не удалось снять pending alarm восстановления")
        }
    }

    private fun makePendingIntent(reason: String, launchUi: Boolean): PendingIntent {
        val intent = Intent(context, AppRecoveryReceiver::class.java).apply {
            action = AppRecoveryReceiver.ACTION_RECOVER_APP
            setPackage(context.packageName)
            putExtra(AppRecoveryReceiver.EXTRA_REASON, reason)
            putExtra(AppRecoveryReceiver.EXTRA_LAUNCH_UI, launchUi)
            putExtra(AppRecoveryReceiver.EXTRA_KEEP_IN_FOREGROUND, false)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
