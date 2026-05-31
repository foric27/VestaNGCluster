package ru.foric27.cluster

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

private const val APP_SETTINGS_STORE_NAME = "cluster_flow_prefs"

private val Context.appSettingsDataStore by preferencesDataStore(
    name = APP_SETTINGS_STORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, APP_SETTINGS_STORE_NAME))
    },
)

/**
 * Пользовательские настройки UI, которые влияют на штатный cluster-stream.
 */
internal object AppSettings {

    private const val TAG = "AppSettings"
    private const val KEY_STREAM_MODE = "stream_mode"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val KEY_COLLAPSE_ON_LAUNCH = "collapse_on_launch"

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

    enum class UpdateChannel(
        val prefValue: String,
    ) {
        ROLLING("rolling"),
        STABLE("stable"),
        ;

        companion object {
            fun fromPref(value: String?): UpdateChannel {
                val normalized = value?.trim()?.lowercase()
                return entries.firstOrNull { it.prefValue == normalized } ?: ROLLING
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
        val saved = readSavedMode(context)
        if (!saved.isNullOrBlank()) {
            return UiStreamMode.fromPref(saved)
        }

        return try {
            UiStreamMode.fromSetting(
                Settings.Global.getInt(context.contentResolver, SyncHandler.STREAM_MODE_PARAM),
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось прочитать режим из Settings.Global, использую NAV")
            UiStreamMode.NAV
        }
    }

    fun saveSelectedMode(context: Context, mode: UiStreamMode): Boolean {
        return try {
            val appContext = context.applicationContext
            runBlocking {
                appContext.appSettingsDataStore.edit { preferences ->
                    preferences[stringPreferencesKey(KEY_STREAM_MODE)] = mode.prefValue
                }
            }
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось сохранить режим ${mode.prefValue} в DataStore")
            false
        }
    }

    fun getSelectedUpdateChannel(context: Context): UpdateChannel {
        return UpdateChannel.fromPref(readStringPref(context, KEY_UPDATE_CHANNEL))
    }

    fun saveSelectedUpdateChannel(context: Context, channel: UpdateChannel): Boolean {
        return saveStringPref(context, KEY_UPDATE_CHANNEL, channel.prefValue)
    }

    fun isCollapseOnLaunchEnabled(context: Context): Boolean {
        return readBooleanPref(context, KEY_COLLAPSE_ON_LAUNCH, false)
    }

    fun saveCollapseOnLaunchEnabled(context: Context, enabled: Boolean): Boolean {
        return saveBooleanPref(context, KEY_COLLAPSE_ON_LAUNCH, enabled)
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
            Timber.tag(TAG).w(se, "Нет прав на прямую запись в Settings.Global")
            SettingsWriteResult(
                false,
                context.getString(
                    R.string.app_settings_details_no_global_write_permission_fmt,
                    se.message ?: context.getString(R.string.common_unknown),
                ),
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Ошибка прямой записи режима в Settings.Global")
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

    private fun readSavedMode(context: Context): String? {
        return readStringPref(context, KEY_STREAM_MODE)
    }

    private fun readStringPref(context: Context, key: String): String? {
        val appContext = context.applicationContext
        return runBlocking {
            appContext.appSettingsDataStore.data.first()[stringPreferencesKey(key)]
        }
    }

    private fun readBooleanPref(context: Context, key: String, defaultValue: Boolean): Boolean {
        val appContext = context.applicationContext
        return runBlocking {
            appContext.appSettingsDataStore.data.first()[booleanPreferencesKey(key)] ?: defaultValue
        }
    }

    private fun saveStringPref(context: Context, key: String, value: String): Boolean {
        return try {
            val appContext = context.applicationContext
            runBlocking {
                appContext.appSettingsDataStore.edit { preferences ->
                    preferences[stringPreferencesKey(key)] = value
                }
            }
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось сохранить ключ $key в DataStore")
            false
        }
    }

    private fun saveBooleanPref(context: Context, key: String, value: Boolean): Boolean {
        return try {
            val appContext = context.applicationContext
            runBlocking {
                appContext.appSettingsDataStore.edit { preferences ->
                    preferences[booleanPreferencesKey(key)] = value
                }
            }
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось сохранить булевый ключ $key в DataStore")
            false
        }
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
            val saved = readSavedMode(context)
            if (!saved.isNullOrBlank()) {
                ClusterMode.fromPref(saved)
            } else {
                ClusterMode.fromSetting(
                    Settings.Global.getInt(context.contentResolver, SyncHandler.STREAM_MODE_PARAM),
                )
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось прочитать cluster mode, использую CLASSIC_NAV")
            ClusterMode.CLASSIC_NAV
        }
    }
}
