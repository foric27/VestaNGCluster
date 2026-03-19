package ru.foric27.cluster

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Автостарт сервиса после загрузки устройства, после обновления приложения
 * и обновление FTP-поиска при вставке или извлечении USB-накопителя.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        RuntimeConfig.init(context.applicationContext)
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleServiceAutostart(context, action)

            Intent.ACTION_MEDIA_MOUNTED -> handleUsbMounted(context, intent)
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT -> handleUsbRemoved(context, intent)
        }
    }

    private fun handleServiceAutostart(context: Context, action: String) {
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
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
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

    private fun handleUsbMounted(context: Context, intent: Intent) {
        val path = intent.data?.path.orEmpty()
        if (!isUsbPath(path)) {
            Log.i(TAG, "Пропускаю mount не-USB носителя: $path")
            return
        }
        val result = UpdateServerManager.handleUsbInserted(context.applicationContext)
        Log.i(TAG, "USB вставлен: $path, результат FTP: ${result.message}")
    }

    private fun handleUsbRemoved(context: Context, intent: Intent) {
        val path = intent.data?.path.orEmpty()
        if (path.isNotBlank() && !isUsbPath(path)) {
            Log.i(TAG, "Пропускаю remove не-USB носителя: $path")
            return
        }
        val result = UpdateServerManager.handleUsbRemoved(context.applicationContext)
        Log.i(TAG, "USB извлечён: $path, результат FTP: ${result.message}")
    }

    private fun isUsbPath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.lowercase()
        if (!normalized.startsWith("/storage/")) return false
        if (normalized.startsWith("/storage/emulated")) return false
        if (normalized.startsWith("/storage/self")) return false
        if (normalized.startsWith("/storage/enc_emulated")) return false
        return true
    }

    private companion object {
        private const val TAG = "BootReceiver"
        private const val RECOVERY_DELAY_MS = 1_500L
    }
}
