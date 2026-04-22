package ru.foric27.cluster

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

internal class MainAccessPreflight(
    private val activity: AppCompatActivity,
    private val showNotice: (String, Boolean) -> Unit,
    private val onAllFilesAccessGranted: () -> Unit,
) {

    private var hadAllFilesAccess = false
    private var hadBatteryOptimizationBypass = false
    private var notificationsPermissionPending = false
    private var readStoragePermissionPending = false

    fun run() {
        if (notificationsPermissionPending) return
        if (readStoragePermissionPending) return
        if (ensureNotificationsPermission()) return
        if (ensureReadStorageAccess()) return
        if (ensureAllFilesAccess()) return
        if (ensureBatteryOptimizationBypass()) return
        showNotice(activity.getString(R.string.main_permissions_all_set), false)
    }

    fun handlePermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        when (requestCode) {
            REQUEST_NOTIFICATIONS_CODE -> {
                notificationsPermissionPending = false
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    showNotice(activity.getString(R.string.main_notifications_granted), false)
                    run()
                    return true
                }

                if (Build.VERSION.SDK_INT >= 33) {
                    showNotice(activity.getString(R.string.main_notifications_missing), true)
                    if (!activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            })
                        }.onFailure { error ->
                            Log.w(TAG, "Не удалось открыть настройки уведомлений приложения", error)
                            showNotice(activity.getString(R.string.main_notifications_missing_settings_message), true)
                        }
                    }
                }
                return true
            }
            REQUEST_READ_STORAGE_CODE -> {
                readStoragePermissionPending = false
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    showNotice(activity.getString(R.string.main_read_storage_granted), false)
                    run()
                    return true
                }
                showNotice(activity.getString(R.string.main_read_storage_missing), true)
                return true
            }
            else -> return false
        }
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
        return true
    }

    private fun ensureReadStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false
        if (hasReadStoragePermission()) return false
        showNotice(activity.getString(R.string.main_read_storage_missing), true)
        readStoragePermissionPending = true
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            REQUEST_READ_STORAGE_CODE,
        )
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
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS_CODE,
        )
        return true
    }

    private fun hasNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openManageAllFilesAccessSettings() {
        runCatching {
            activity.startActivity(StorageAccessManager.buildManageAllFilesAccessIntent(activity))
        }.recoverCatching {
            activity.startActivity(StorageAccessManager.buildManageAllFilesAccessFallbackIntent())
        }.onFailure { error ->
            Log.w(TAG, "Не удалось открыть настройки доступа ко всем файлам", error)
            showNotice(activity.getString(R.string.main_open_files_settings_failed), true)
        }
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            activity.startActivity(BatteryOptimizationManager.buildRequestIgnoreOptimizationsIntent(activity))
        }.recoverCatching {
            activity.startActivity(BatteryOptimizationManager.buildBatteryOptimizationSettingsIntent())
        }.onFailure { error ->
            Log.w(TAG, "Не удалось открыть настройки энергосбережения", error)
            showNotice(activity.getString(R.string.main_open_battery_settings_failed), true)
        }
    }

    private companion object {
        private const val TAG = "MainAccessPreflight"
        const val REQUEST_NOTIFICATIONS_CODE = 10
        const val REQUEST_READ_STORAGE_CODE = 11
    }
}
