package ru.foric27.cluster.config
import ru.foric27.cluster.R

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.runtimeConfigDataStore by preferencesDataStore(
    name = RuntimeConfig.PREFS_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, RuntimeConfig.PREFS_NAME))
    },
)

/**
 * Хранилище runtime-конфигурации на базе DataStore Preferences.
 *
 * Реализует [RuntimeConfigRepository]: чтение, запись и сброс настроек.
 * Автоматически мигрирует данные из SharedPreferences.
 */
internal object RuntimeConfigStore : RuntimeConfigRepository {

    override fun load(context: Context, specs: List<RuntimeConfig.FieldSpec>): Map<String, String> {
        val appContext = context.applicationContext
        val preferences = runBlocking { appContext.runtimeConfigDataStore.data.first() }
        return buildMap {
            specs.forEach { spec ->
                val value = preferences[stringPreferencesKey(spec.key)]
                if (value != null) {
                    put(spec.key, value)
                }
            }
        }
    }

    override fun saveField(
        context: Context,
        spec: RuntimeConfig.FieldSpec,
        rawValue: String,
        title: String,
    ): RuntimeConfig.SaveResult {
        val normalizedValue = normalizeFieldValue(spec, rawValue) ?: return when (spec.type) {
            RuntimeConfig.ValueType.INT -> RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_int_field_fmt, title), spec.key)
            RuntimeConfig.ValueType.LONG -> RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_long_field_fmt, title), spec.key)
            RuntimeConfig.ValueType.BOOLEAN -> RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_boolean_field_fmt, title), spec.key)
            RuntimeConfig.ValueType.STRING -> RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_string_field_fmt, title), spec.key)
        }

        try {
            val appContext = context.applicationContext
            runBlocking {
                appContext.runtimeConfigDataStore.edit { preferences ->
                    preferences[stringPreferencesKey(spec.key)] = normalizedValue
                }
            }
        } catch (_: Throwable) {
            return RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_save_setting_fmt, title), spec.key)
        }
        return RuntimeConfig.SaveResult(true, context.getString(R.string.runtime_save_setting_ok_fmt, title), spec.key, normalizedValue)
    }

    override fun resetToDefaults(context: Context): Boolean {
        return try {
            val appContext = context.applicationContext
            runBlocking {
                appContext.runtimeConfigDataStore.edit { preferences ->
                    preferences.clear()
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun parseBooleanValue(rawValue: String?, default: Boolean): Boolean {
        return when ((rawValue ?: default.toString()).trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    private fun normalizeFieldValue(spec: RuntimeConfig.FieldSpec, rawValue: String): String? {
        val input = rawValue.trim()
        return when (spec.type) {
            RuntimeConfig.ValueType.STRING -> input
            RuntimeConfig.ValueType.INT -> {
                val source = input.ifBlank { spec.defaultValue }
                source.toIntOrNull()?.toString()
            }

            RuntimeConfig.ValueType.LONG -> {
                val source = input.ifBlank { spec.defaultValue }
                source.toLongOrNull()?.toString()
            }

            RuntimeConfig.ValueType.BOOLEAN -> normalizeBooleanString(input, spec)
        }
    }

    private fun normalizeBooleanString(raw: String, spec: RuntimeConfig.FieldSpec): String? {
        val source = raw.ifBlank { spec.defaultValue }.trim().lowercase()
        return when (source) {
            "true", "1", "yes", "on" -> "true"
            "false", "0", "no", "off" -> "false"
            else -> null
        }
    }

}
