package ru.foric27.cluster.util
import ru.foric27.cluster.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Управление battery optimization.
 *
 * Проверяет и запрашивает игнорирование оптимизации батареи
 * для бесперебойной фоновой работы сервиса.
 */
/**
 * Управление настройками энергосбережения (battery optimization).
 *
 * Проверяет, игнорируется ли приложение оптимизациями, и формирует
 * Intents для запроса соответствующего разрешения.
 */
object BatteryOptimizationManager {

    /**
     * Проверяет, игнорируется ли приложение системными оптимизациями батареи.
     *
     * @param context контекст приложения
     * @return true, если оптимизации отключены для приложения
     */
    fun isIgnoringOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Создаёт Intent для запроса игнорирования оптимизаций батареи.
     *
     * @param context контекст приложения
     * @return Intent для запуска системного диалога
     */
    fun buildRequestIgnoreOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Создаёт Intent для открытия настроек оптимизации батареи.
     *
     * @return Intent для запуска настроек
     */
    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Проверяет, доступен ли на устройстве поток настроек battery optimization.
     *
     * @param context контекст приложения
     * @return true, если можно запросить отключение оптимизаций
     */
    fun isSettingsFlowAvailable(context: Context): Boolean {
        val packageManager = context.packageManager
        val requestIntent = buildRequestIgnoreOptimizationsIntent(context)
        val settingsIntent = buildBatteryOptimizationSettingsIntent()
        return requestIntent.resolveActivity(packageManager) != null ||
            settingsIntent.resolveActivity(packageManager) != null
    }

    /**
     * Возвращает сообщение для пользователя о необходимости отключить оптимизации.
     *
     * @param context контекст приложения
     * @return локализованное сообщение
     */
    fun buildMissingAccessMessage(context: Context): String {
        return context.getString(R.string.battery_optimization_missing_message)
    }
}
