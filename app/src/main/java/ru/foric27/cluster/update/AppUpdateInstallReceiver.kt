package ru.foric27.cluster.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * Получает статус установки APK через PackageInstaller Session API.
 * PackageInstaller вызывает onReceive с extras PACKAGE_NAME, STATUS, STATUS_MESSAGE.
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val status = intent?.getIntExtra("android.content.pm.extra.STATUS", -1) ?: return
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
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    Timber.tag(TAG).i("Запрошен пользовательский confirm при установке APK")
                } else {
                    Timber.tag(TAG).w("STATUS_PENDING_USER_ACTION без confirmation intent")
                }
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
