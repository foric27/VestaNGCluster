package ru.foric27.cluster

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Автостарт сервиса после загрузки устройства, после обновления приложения
 * и обновление FTP-поиска при вставке или извлечении USB-накопителя.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverExecutor.execute {
            try {
                handleAction(appContext, action, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "$action: не удалось обработать broadcast", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Маршрутизирует broadcast в быстрый service/recovery-path, не выполняя
     * тяжёлый USB/FTP-поиск прямо внутри onReceive.
     */
    private fun handleAction(context: Context, action: String, intent: Intent?) {
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleServiceAutostart(context, action)

            Intent.ACTION_MEDIA_MOUNTED -> requestUsbRefresh(context, action, intent, removed = false)
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT -> requestUsbRefresh(context, action, intent, removed = true)
        }
    }

    private fun handleServiceAutostart(context: Context, action: String) {
        logMissingPrerequisites(context, action)
        try {
            UdpStreamService.startServiceCompat(context)
            Log.i(TAG, "$action: foreground-сервис запущен")
        } catch (t: Throwable) {
            if (isForegroundStartRestricted(t)) {
                Log.i(TAG, "$action: прямой foreground-старт сейчас запрещён, планирую мягкое восстановление")
                scheduleDeferredRecovery(context, action)
                return
            }
            Log.w(TAG, "$action: не удалось запустить foreground-сервис", t)
        }
    }

    private fun logMissingPrerequisites(context: Context, action: String) {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Log.w(TAG, "$action: нет MANAGE_EXTERNAL_STORAGE, FTP и update-path будут ограничены")
        }
        if (!BatteryOptimizationManager.isIgnoringOptimizations(context)) {
            Log.w(TAG, "$action: приложение не выведено из энергосбережения, автоподъём после сна может быть нестабилен")
        }
    }

    private fun scheduleDeferredRecovery(context: Context, action: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = AppRecoveryReceiver.createPendingIntent(
                context = context.applicationContext,
                requestCode = RuntimeConfig.Service.SERVICE_RECOVERY_REQUEST_CODE,
                reason = action,
                launchUi = false,
            )
            val triggerAtMillis = SystemClock.elapsedRealtime() + RECOVERY_DELAY_MS
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            Log.i(TAG, "$action: отложенное восстановление приложения запланировано через ${RECOVERY_DELAY_MS}мс")
        } catch (t: Throwable) {
            Log.w(TAG, "$action: не удалось запланировать отложенное восстановление", t)
        }
    }

    private fun isForegroundStartRestricted(error: Throwable): Boolean {
        val name = error.javaClass.name
        val message = error.message.orEmpty()
        return name.contains("ForegroundServiceStartNotAllowedException") ||
            message.contains("ForegroundServiceStartNotAllowedException")
    }

    /**
     * USB mount/remove сводится к быстрому сигналу сервису обновить FTP-состояние.
     * Фактический поиск update-пакета уже выполняется сервисом в фоне.
     */
    private fun requestUsbRefresh(context: Context, action: String, intent: Intent?, removed: Boolean) {
        val path = intent?.data?.path.orEmpty()
        if (!UsbStoragePathMatcher.isUsbStoragePath(path)) {
            if (path.isNotBlank()) {
                Log.i(TAG, "$action: пропускаю не-USB носитель: $path")
            }
            return
        }

        UdpStreamService.refreshFtpCompat(context)
        Log.i(
            TAG,
            if (removed) {
                "$action: запрошено обновление FTP после извлечения USB: $path"
            } else {
                "$action: запрошено обновление FTP после подключения USB: $path"
            },
        )
    }

    private companion object {
        private const val TAG = "BootReceiver"
        private const val RECOVERY_DELAY_MS = 1_500L
        private val receiverExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BootReceiverWorker") }
    }
}
