package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
            Log.w(TAG, "$action: не удалось запустить foreground-сервис", t)
        }
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
    }
}
