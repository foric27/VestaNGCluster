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
object BatteryOptimizationManager {

    fun isIgnoringOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildRequestIgnoreOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun isSettingsFlowAvailable(context: Context): Boolean {
        val packageManager = context.packageManager
        val requestIntent = buildRequestIgnoreOptimizationsIntent(context)
        val settingsIntent = buildBatteryOptimizationSettingsIntent()
        return requestIntent.resolveActivity(packageManager) != null ||
            settingsIntent.resolveActivity(packageManager) != null
    }

    fun buildMissingAccessMessage(context: Context): String {
        return context.getString(R.string.battery_optimization_missing_message)
    }
}
