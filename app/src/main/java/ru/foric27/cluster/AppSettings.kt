package ru.foric27.cluster

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Пользовательские настройки UI, которые влияют на штатный cluster-stream.
 *
 * Храним:
 * - режим видеопотока комбинации приборов;
 */
object AppSettings {

    private const val TAG = "AppSettings"
    private const val PREFS_NAME = "cluster_flow_prefs"
    private const val KEY_STREAM_MODE = "stream_mode"

    enum class UiStreamMode(
        val prefValue: String,
        val settingValue: Int,
    ) {
        NAV("nav", 1),
        MED("med", 2),
        ABS("abs", 0),
        ;

        companion object {
            fun fromPref(value: String?): UiStreamMode {
                return values().firstOrNull { it.prefValue == value } ?: NAV
            }

            fun fromSetting(value: Int): UiStreamMode {
                return when (value) {
                    0 -> ABS
                    1, 3, 5, 7 -> NAV
                    2, 4, 6, 8 -> MED
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
        var source = if (prefSaved) "SharedPreferences" else "Память приложения"
        var details = if (prefSaved) {
            "Режим сохранён в SharedPreferences"
        } else {
            "Не удалось сохранить режим в SharedPreferences"
        }

        val directWrite = putModeToSettingsDirect(context, mode)
        if (directWrite.success) {
            return ApplyModeResult(
                ok = true,
                savedLocally = prefSaved,
                mode = mode,
                source = if (prefSaved) "SharedPreferences + Settings.Global" else "Settings.Global",
                details = directWrite.details,
            )
        }

        if (prefSaved) {
            source = "SharedPreferences"
            details = buildString {
                append("Прямое применение режима не подтверждено. Выбор сохранён локально без root.")
                append('\n')
                append("Direct: ")
                append(directWrite.details)
            }
        } else {
            source = "Ошибка сохранения"
            details = buildString {
                append("Не удалось сохранить режим ни локально, ни в Settings.Global.")
                append('\n')
                append("Direct: ")
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
                details = "WRITE_SECURE_SETTINGS не выдан — прямую запись пропускаю",
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
                    details = "Режим сохранён в SharedPreferences и напрямую записан в Settings.Global",
                )
            } else {
                SettingsWriteResult(
                    success = false,
                    details = "Settings.Global.putInt вернул $directOk, readBack=${readBack?.prefValue ?: "null"}",
                )
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Нет прав на прямую запись в Settings.Global", se)
            SettingsWriteResult(false, "Нет прав на запись в Settings.Global: ${se.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Ошибка прямой записи режима в Settings.Global", t)
            SettingsWriteResult(false, "Ошибка прямой записи: ${t.message ?: t.javaClass.simpleName}")
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
}
