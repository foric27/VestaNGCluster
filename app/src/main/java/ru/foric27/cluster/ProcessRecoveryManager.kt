package ru.foric27.cluster

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ProcessRecoveryManager {

    private const val TAG = "ProcessRecovery"
    private const val PREFS_NAME = "process_recovery"
    private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"
    private const val KEY_CRASH_SUPPRESSED_UNTIL = "crash_suppressed_until"
    private const val REQUEST_CODE = 41_092

    data class RecoveryDebugState(
        val crashCountInWindow: Int,
        val suppressedUntilElapsedMs: Long,
        val timestamps: List<Long>,
    )

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                RuntimeConfig.init(appContext)
                writeCrashLog(appContext, thread, throwable)
                LogcatExporter.exportOnCrash(appContext)
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

    fun readDebugState(context: Context): RecoveryDebugState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val timestamps = prefs.getString(KEY_CRASH_TIMESTAMPS, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { (now - it) <= RuntimeConfig.Service.PROCESS_CRASH_WINDOW_MS }
        return RecoveryDebugState(
            crashCountInWindow = timestamps.size,
            suppressedUntilElapsedMs = prefs.getLong(KEY_CRASH_SUPPRESSED_UNTIL, 0L),
            timestamps = timestamps,
        )
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

    @android.annotation.SuppressLint("NewApi")
    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val crashFile = java.io.File(context.cacheDir, "logs/crash-log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val content = buildString {
            appendLine("=== CRASH LOG ===")
            appendLine("timestamp=$timestamp")
            @Suppress("DEPRECATION")
            val threadId = thread.id
            appendLine("thread=${thread.name}#$threadId")
            appendLine("exception=${throwable.javaClass.name}")
            appendLine("message=${throwable.message}")
            appendLine("stacktrace=")
            appendLine(LogSanitizer.sanitize(Log.getStackTraceString(throwable)))
            appendLine()
            appendLine("=== IN-MEMORY ERROR LOG ===")
            val memoryLogs = InMemoryLogBuffer.toList()
            if (memoryLogs.isEmpty()) {
                appendLine("<empty>")
            } else {
                memoryLogs.forEach { line ->
                    appendLine(line.toString())
                }
            }
            appendLine("=== END CRASH LOG ===")
        }
        runCatching {
            crashFile.parentFile?.mkdirs()
            crashFile.writeText(content, Charsets.UTF_8)
        }.onFailure { Timber.tag(TAG).e(it, "Не удалось записать crash log") }
    }

    private fun scheduleRecovery(context: Context, reason: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            Timber.tag(TAG).w("SCHEDULE_EXACT_ALARM не выдано, пропускаю восстановление после падения")
            return
        }
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
