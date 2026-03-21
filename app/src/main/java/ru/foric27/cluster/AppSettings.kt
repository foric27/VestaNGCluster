package ru.foric27.cluster

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Пользовательские настройки UI, которые влияют на штатный cluster-stream.
 */
object AppSettings {

    private const val TAG = "AppSettings"
    private const val PREFS_NAME = "cluster_flow_prefs"
    private const val KEY_STREAM_MODE = "stream_mode"

    enum class UiStreamMode(
        val clusterMode: ClusterMode,
    ) {
        NAV(ClusterMode.CLASSIC_NAV),
        MED(ClusterMode.CLASSIC_MEDIA),
        ABS(ClusterMode.TRIP),
        ;

        val prefValue: String
            get() = clusterMode.prefValue

        val settingValue: Int
            get() = clusterMode.settingValue

        companion object {
            fun fromPref(value: String?): UiStreamMode {
                return fromClusterMode(ClusterMode.fromPref(value))
            }

            fun fromSetting(value: Int): UiStreamMode {
                return fromClusterMode(ClusterMode.fromSetting(value))
            }

            private fun fromClusterMode(mode: ClusterMode): UiStreamMode {
                return when {
                    mode.isMedia -> MED
                    mode.isTrip -> ABS
                    else -> NAV
                }
            }
        }
    }

    data class ApplyModeResult(
        val ok: Boolean,
        val savedLocally: Boolean,
        val mode: UiStreamMode,
        val source: String,
        val details: String,
    )

    fun getSelectedMode(context: Context): UiStreamMode {
        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_STREAM_MODE, null)
        if (!saved.isNullOrBlank()) {
            return UiStreamMode.fromPref(saved)
        }

        return try {
            UiStreamMode.fromSetting(
                Settings.Global.getInt(context.contentResolver, SyncHandler.STREAM_MODE_PARAM),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось прочитать режим из Settings.Global, использую NAV", t)
            UiStreamMode.NAV
        }
    }

    fun saveSelectedMode(context: Context, mode: UiStreamMode): Boolean {
        return try {
            getPrefs(context)
                .edit()
                .putString(KEY_STREAM_MODE, mode.prefValue)
                .commit()
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось сохранить режим ${mode.prefValue} в SharedPreferences", t)
            false
        }
    }

    fun applySelectedMode(context: Context, mode: UiStreamMode): ApplyModeResult {
        val prefSaved = saveSelectedMode(context, mode)
        var source = if (prefSaved) {
            context.getString(R.string.app_settings_source_shared_prefs)
        } else {
            context.getString(R.string.app_settings_source_memory)
        }
        var details = if (prefSaved) {
            context.getString(R.string.app_settings_details_saved_in_prefs)
        } else {
            context.getString(R.string.app_settings_details_failed_to_save_in_prefs)
        }

        val directWrite = putModeToSettingsDirect(context, mode)
        if (directWrite.success) {
            return ApplyModeResult(
                ok = true,
                savedLocally = prefSaved,
                mode = mode,
                source = if (prefSaved) {
                    context.getString(R.string.app_settings_source_shared_prefs_and_global)
                } else {
                    context.getString(R.string.app_settings_source_settings_global)
                },
                details = directWrite.details,
            )
        }

        if (prefSaved) {
            source = context.getString(R.string.app_settings_source_shared_prefs)
            details = buildString {
                append(context.getString(R.string.app_settings_details_saved_locally_without_root))
                append('\n')
                append(context.getString(R.string.app_settings_direct_prefix))
                append(directWrite.details)
            }
        } else {
            source = context.getString(R.string.app_settings_source_save_error)
            details = buildString {
                append(context.getString(R.string.app_settings_details_failed_to_save_anywhere))
                append('\n')
                append(context.getString(R.string.app_settings_direct_prefix))
                append(directWrite.details)
            }
        }

        return ApplyModeResult(
            ok = prefSaved,
            savedLocally = prefSaved,
            mode = mode,
            source = source,
            details = details,
        )
    }

    private fun putModeToSettingsDirect(context: Context, mode: UiStreamMode): SettingsWriteResult {
        if (!canWriteGlobalSettingsDirect(context)) {
            return SettingsWriteResult(
                success = false,
                details = context.getString(R.string.app_settings_details_write_secure_settings_missing),
            )
        }

        return try {
            val directOk = Settings.Global.putInt(
                context.contentResolver,
                SyncHandler.STREAM_MODE_PARAM,
                mode.settingValue,
            )
            val readBack = readModeFromSettings(context)
            if (directOk && readBack == mode) {
                SettingsWriteResult(
                    success = true,
                    details = context.getString(R.string.app_settings_details_saved_in_prefs_and_global),
                )
            } else {
                SettingsWriteResult(
                    success = false,
                    details = context.getString(
                        R.string.app_settings_details_global_put_result_fmt,
                        directOk.toString(),
                        readBack?.prefValue ?: context.getString(R.string.common_null),
                    ),
                )
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Нет прав на прямую запись в Settings.Global", se)
            SettingsWriteResult(
                false,
                context.getString(
                    R.string.app_settings_details_no_global_write_permission_fmt,
                    se.message ?: context.getString(R.string.common_unknown),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Ошибка прямой записи режима в Settings.Global", t)
            SettingsWriteResult(
                false,
                context.getString(
                    R.string.app_settings_details_direct_write_error_fmt,
                    t.message ?: t.javaClass.simpleName,
                ),
            )
        }
    }

    private fun readModeFromSettings(context: Context): UiStreamMode? {
        return try {
            UiStreamMode.fromSetting(
                Settings.Global.getInt(context.contentResolver, SyncHandler.STREAM_MODE_PARAM),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun canWriteGlobalSettingsDirect(context: Context): Boolean {
        return try {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private data class SettingsWriteResult(
        val success: Boolean,
        val details: String,
    )

    fun getSelectedClusterMode(context: Context): ClusterMode {
        return try {
            val prefs = getPrefs(context)
            val saved = prefs.getString(KEY_STREAM_MODE, null)
            if (!saved.isNullOrBlank()) {
                ClusterMode.fromPref(saved)
            } else {
                ClusterMode.fromSetting(
                    Settings.Global.getInt(context.contentResolver, SyncHandler.STREAM_MODE_PARAM),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось прочитать cluster mode, использую CLASSIC_NAV", t)
            ClusterMode.CLASSIC_NAV
        }
    }
}
