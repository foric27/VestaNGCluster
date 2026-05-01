package ru.foric27.cluster

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

internal class MainAccessPreflight(
    private val activity: AppCompatActivity,
    private val showNotice: (String, Boolean) -> Unit,
    private val onAllFilesAccessGranted: () -> Unit,
    private val requestNotificationsPermission: () -> Unit,
    private val requestReadStoragePermission: () -> Unit,
    private val launchSettingsIntent: (Intent, Intent?, (Throwable) -> Unit) -> Unit,
) {

    private var hadAllFilesAccess = false
    private var hadBatteryOptimizationBypass = false
    private var exactAlarmJustRequested = false
    private var notificationsPermissionPending = false
    private var readStoragePermissionPending = false

    fun run() {
        if (notificationsPermissionPending) return
        if (readStoragePermissionPending) return
        if (ensureNotificationsPermission()) return
        if (ensureReadStorageAccess()) return
        if (ensureNotificationListenerAccess()) return
        if (ensureAllFilesAccess()) return
        if (ensureBatteryOptimizationBypass()) return
        if (ensureExactAlarmPermission()) return
        showNotice(activity.getString(R.string.main_permissions_all_set), false)
    }

    fun handleNotificationsPermissionResult(granted: Boolean) {
        notificationsPermissionPending = false
        if (granted) {
            showNotice(activity.getString(R.string.main_notifications_granted), false)
            run()
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            showNotice(activity.getString(R.string.main_notifications_missing), true)
            if (!activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                launchSettingsIntent(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    },
                    null,
                ) { error ->
                    Timber.tag(TAG).w(error, "Не удалось открыть настройки уведомлений приложения")
                    showNotice(activity.getString(R.string.main_notifications_missing_settings_message), true)
                }
            }
        }
    }

    fun handleReadStoragePermissionResult(granted: Boolean) {
        readStoragePermissionPending = false
        if (granted) {
            showNotice(activity.getString(R.string.main_read_storage_granted), false)
            run()
            return
        }
        showNotice(activity.getString(R.string.main_read_storage_missing), true)
    }

    fun handleSettingsActivityResult() {
        run()
    }

    fun isNotificationsPermissionPending(): Boolean = notificationsPermissionPending

    fun isReadyToBackground(): Boolean {
        if (notificationsPermissionPending) return false
        if (readStoragePermissionPending) return false
        if (!hasNotificationsPermission()) return false
        if (!hasReadStoragePermission()) return false
        if (!StorageAccessManager.isAllFilesAccessGranted()) return false
        if (BatteryOptimizationManager.isSettingsFlowAvailable(activity) && !BatteryOptimizationManager.isIgnoringOptimizations(activity)) {
            return false
        }
        if (!hasExactAlarmPermission()) return false
        return true
    }

    private fun ensureReadStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false
        if (hasReadStoragePermission()) return false
        showNotice(activity.getString(R.string.main_read_storage_missing), true)
        readStoragePermissionPending = true
        requestReadStoragePermission()
        return true
    }

    private fun hasReadStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureAllFilesAccess(): Boolean {
        val hasAccess = StorageAccessManager.isAllFilesAccessGranted()
        val accessJustGranted = hasAccess && !hadAllFilesAccess
        hadAllFilesAccess = hasAccess

        if (!hasAccess) {
            showNotice(StorageAccessManager.buildMissingAccessMessage(activity), true)
            openManageAllFilesAccessSettings()
            return true
        }

        if (accessJustGranted) {
            showNotice(activity.getString(R.string.main_all_files_access_granted), false)
            onAllFilesAccessGranted()
        }
        return false
    }

    private fun ensureBatteryOptimizationBypass(): Boolean {
        val bypassGranted = BatteryOptimizationManager.isIgnoringOptimizations(activity)
        val accessJustGranted = bypassGranted && !hadBatteryOptimizationBypass
        hadBatteryOptimizationBypass = bypassGranted

        if (!BatteryOptimizationManager.isSettingsFlowAvailable(activity)) {
            showNotice(activity.getString(R.string.main_battery_optimization_not_supported), false)
            return false
        }

        if (!bypassGranted) {
            showNotice(BatteryOptimizationManager.buildMissingAccessMessage(activity), true)
            openBatteryOptimizationSettings()
            return true
        }

        if (accessJustGranted) {
            showNotice(activity.getString(R.string.main_battery_optimization_granted), false)
        }
        return false
    }

    private fun ensureNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (hasNotificationsPermission()) return false
        showNotice(activity.getString(R.string.main_notifications_missing), true)
        notificationsPermissionPending = true
        requestNotificationsPermission()
        return true
    }

    private fun hasNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openManageAllFilesAccessSettings() {
        launchSettingsIntent(
            StorageAccessManager.buildManageAllFilesAccessIntent(activity),
            StorageAccessManager.buildManageAllFilesAccessFallbackIntent(),
        ) { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть настройки доступа ко всем файлам")
            showNotice(activity.getString(R.string.main_open_files_settings_failed), true)
        }
    }

    private fun openBatteryOptimizationSettings() {
        launchSettingsIntent(
            BatteryOptimizationManager.buildRequestIgnoreOptimizationsIntent(activity),
            BatteryOptimizationManager.buildBatteryOptimizationSettingsIntent(),
        ) { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть настройки энергосбережения")
            showNotice(activity.getString(R.string.main_open_battery_settings_failed), true)
        }
    }

    private fun ensureNotificationListenerAccess(): Boolean {
        if (hasNotificationListenerAccess()) return false
        showNotice(activity.getString(R.string.main_notification_listener_missing), true)
        openNotificationListenerSettings()
        return true
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(activity.packageName)
    }

    private fun openNotificationListenerSettings() {
        launchSettingsIntent(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            null,
        ) { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть настройки доступа к уведомлениям")
            showNotice(activity.getString(R.string.main_open_notification_listener_settings_failed), true)
        }
    }

    private fun ensureExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (hasExactAlarmPermission()) return false
        if (exactAlarmJustRequested) {
            exactAlarmJustRequested = false
            return false
        }
        showNotice(activity.getString(R.string.main_exact_alarm_missing), true)
        exactAlarmJustRequested = true
        launchSettingsIntent(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            null,
        ) { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть настройки exact alarm")
        }
        return true
    }

    private fun hasExactAlarmPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            (activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
    }

    private companion object {
        private const val TAG = "MainAccessPreflight"
    }
}
