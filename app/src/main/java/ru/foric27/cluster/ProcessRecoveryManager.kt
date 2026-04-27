package ru.foric27.cluster

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

internal object ProcessRecoveryManager {

    private const val TAG = "ProcessRecovery"
    private const val PREFS_NAME = "process_recovery"
    private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"
    private const val KEY_CRASH_SUPPRESSED_UNTIL = "crash_suppressed_until"
    private const val REQUEST_CODE = 41_092

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                RuntimeConfig.init(appContext)
                if (shouldScheduleRecovery(appContext)) {
                    scheduleRecovery(appContext, throwable.javaClass.simpleName.ifBlank { "uncaught_exception" })
                } else {
                    Timber.tag(TAG).w("Автовосстановление после падения временно подавлено из-за crash loop")
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Не удалось запланировать восстановление после падения процесса")
            }

            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    Timber.tag(TAG).e(throwable, "Процесс завершён после необработанного исключения")
                    kotlin.system.exitProcess(2)
                }
        }
    }

    private fun shouldScheduleRecovery(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val suppressedUntil = prefs.getLong(KEY_CRASH_SUPPRESSED_UNTIL, 0L)
        if (suppressedUntil > now) {
            return false
        }

        val timestamps = prefs.getString(KEY_CRASH_TIMESTAMPS, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { (now - it) <= RuntimeConfig.Service.PROCESS_CRASH_WINDOW_MS }
            .toMutableList()
        timestamps += now

        val suppress = timestamps.size >= RuntimeConfig.Service.PROCESS_CRASH_MAX_IN_WINDOW
        prefs.edit()
            .putString(KEY_CRASH_TIMESTAMPS, timestamps.joinToString(","))
            .putLong(KEY_CRASH_SUPPRESSED_UNTIL, if (suppress) now + RuntimeConfig.Service.PROCESS_CRASH_SUPPRESS_MS else 0L)
            .apply()
        return !suppress
    }

    private fun scheduleRecovery(context: Context, reason: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = AppRecoveryReceiver.createPendingIntent(
            context = context,
            requestCode = REQUEST_CODE,
            reason = "process_crash:$reason",
            launchUi = false,
        )
        val triggerAtMillis = SystemClock.elapsedRealtime() + RuntimeConfig.Service.PROCESS_CRASH_RECOVERY_DELAY_MS
        if (Build.VERSION.SDK_INT >= 23) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        }
        Timber.tag(TAG).w("Запланировано восстановление после падения процесса, reason=$reason")
    }
}
