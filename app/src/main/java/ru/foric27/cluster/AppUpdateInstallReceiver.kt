package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Получает статус установки APK через PackageInstaller Session API.
 * PackageInstaller вызывает onReceive с extras PACKAGE_NAME, STATUS, STATUS_MESSAGE.
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val status = intent?.getIntExtra(Intent.EXTRA_INTENT, -1) ?: return
        val packageName = intent.getStringExtra("android.content.pm.extra.PACKAGE_NAME").orEmpty()
        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                Timber.tag(TAG).i("APK установлен успешно: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE -> {
                val message = intent.getStringExtra("android.content.pm.extra.STATUS_MESSAGE")
                Timber.tag(TAG).w("Установка APK завершилась ошибкой: $packageName, message=$message")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Timber.tag(TAG).w("Установка APK прервана пользователем: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Timber.tag(TAG).w("Установка APK заблокирована: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Timber.tag(TAG).w("Конфликт при установке APK: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Timber.tag(TAG).w("Несовместимая версия APK: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> {
                Timber.tag(TAG).w("Невалидный APK: $packageName")
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Timber.tag(TAG).w("Недостаточно места для установки: $packageName")
            }
            else -> {
                val message = intent.getStringExtra("android.content.pm.extra.STATUS_MESSAGE")
                Timber.tag(TAG).w("Неизвестный статус установки: status=$status, package=$packageName, message=$message")
            }
        }
    }

    companion object {
        private const val TAG = "AppUpdateInstall"
        const val ACTION_INSTALL_STATUS = "ru.foric27.cluster.action.APP_UPDATE_INSTALL_STATUS"
    }
}
