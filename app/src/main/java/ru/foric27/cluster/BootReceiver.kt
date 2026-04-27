package ru.foric27.cluster

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Автостарт сервиса после загрузки устройства, после обновления приложения
 * и обновление FTP-поиска при вставке или извлечении USB-накопителя.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                handleAction(appContext, action, intent)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "$action: не удалось обработать broadcast")
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
            Timber.tag(TAG).i("$action: foreground-сервис запущен")
        } catch (t: Throwable) {
            if (isForegroundStartRestricted(t)) {
                Timber.tag(TAG).i("$action: прямой foreground-старт сейчас запрещён, планирую мягкое восстановление")
                scheduleDeferredRecovery(context, action)
                return
            }
            Timber.tag(TAG).w(t, "$action: не удалось запустить foreground-сервис")
        }
        try {
            context.startActivity(
                MainActivity.createLaunchIntent(context, keepInForeground = false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Timber.tag(TAG).i("$action: UI поднят, будет свёрнут если разрешения уже выданы")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "$action: не удалось поднять UI (возможно, экран заблокирован)")
        }
    }

    private fun logMissingPrerequisites(context: Context, action: String) {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Timber.tag(TAG).w("$action: нет MANAGE_EXTERNAL_STORAGE, FTP и update-path будут ограничены")
        }
        if (!BatteryOptimizationManager.isIgnoringOptimizations(context)) {
            Timber.tag(TAG).w("$action: приложение не выведено из энергосбережения, автоподъём после сна может быть нестабилен")
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
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Timber.tag(TAG).i("$action: отложенное восстановление приложения запланировано через ${RECOVERY_DELAY_MS}мс")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "$action: не удалось запланировать отложенное восстановление")
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
                Timber.tag(TAG).i("$action: пропускаю не-USB носитель: $path")
            }
            return
        }

        if (removed) {
            UdpStreamService.refreshUsbRemovedFtpCompat(context)
        } else {
            UdpStreamService.refreshUsbFtpCompat(context)
        }
        Timber.tag(TAG).i(if (removed) {
                "$action: запрошено обновление FTP после извлечения USB: $path"
            } else {
                "$action: запрошено обновление FTP после подключения USB: $path"
            },
        )
    }

    private companion object {
        private const val TAG = "BootReceiver"
        private const val RECOVERY_DELAY_MS = 1_500L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
