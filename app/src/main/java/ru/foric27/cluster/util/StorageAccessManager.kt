package ru.foric27.cluster.util
import ru.foric27.cluster.R

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

/**
 * Управление special access для полного доступа к общей памяти.
 */
internal object StorageAccessManager {

    /**
     * Проверяет, выдано ли разрешение MANAGE_ALL_FILES_ACCESS.
     *
     * @return true, если доступ ко всем файлам разрешён
     */
    fun isAllFilesAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Создаёт Intent для открытия настроек доступа ко всем файлам.
     *
     * @param context контекст приложения
     * @return Intent для запуска настроек
     */
    fun buildManageAllFilesAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("NewApi")
    fun buildManageAllFilesAccessFallbackIntent(): Intent {
        return buildManageAllFilesAccessFallbackIntentApi30()
    }

    /**
     * Возвращает сообщение для пользователя о недостающем доступе к файлам.
     *
     * @param context контекст приложения
     * @return локализованное сообщение
     */
    fun buildMissingAccessMessage(context: Context): String {
        return context.getString(R.string.storage_access_missing_message)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildManageAllFilesAccessFallbackIntentApi30(): Intent {
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
