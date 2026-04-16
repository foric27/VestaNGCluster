package ru.foric27.cluster

import android.content.Context

internal object RuntimeConfigStore {

    fun load(context: Context, specs: List<RuntimeConfig.FieldSpec>): Map<String, String> {
        val prefs = getPrefs(context)
        return buildMap {
            specs.forEach { spec ->
                if (prefs.contains(spec.key)) {
                    put(spec.key, prefs.getString(spec.key, spec.defaultValue) ?: spec.defaultValue)
                }
            }
        }
    }

    fun saveField(
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

        val prefs = getPrefs(context)
        val ok = prefs.edit().putString(spec.key, normalizedValue).commit()
        if (!ok) {
            return RuntimeConfig.SaveResult(false, context.getString(R.string.runtime_error_save_setting_fmt, title), spec.key)
        }
        return RuntimeConfig.SaveResult(true, context.getString(R.string.runtime_save_setting_ok_fmt, title), spec.key, normalizedValue)
    }

    fun resetToDefaults(context: Context): Boolean {
        return getPrefs(context).edit().clear().commit()
    }

    fun parseBooleanValue(rawValue: String?, default: Boolean): Boolean {
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

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(RuntimeConfig.PREFS_NAME, Context.MODE_PRIVATE)
}
