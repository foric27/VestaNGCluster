package ru.foric27.cluster.config

import android.content.Context

/**
 * Seam для хранилища runtime-overrides: продакшен читает DataStore, тесты могут
 * подставить in-memory реализацию без Android DataStore и SharedPreferencesMigration.
 */
internal interface RuntimeConfigRepository {
    fun load(context: Context, specs: List<RuntimeConfig.FieldSpec>): Map<String, String>

    fun saveField(
        context: Context,
        spec: RuntimeConfig.FieldSpec,
        rawValue: String,
        title: String,
    ): RuntimeConfig.SaveResult

    fun resetToDefaults(context: Context): Boolean

    fun parseBooleanValue(rawValue: String?, default: Boolean): Boolean
}
