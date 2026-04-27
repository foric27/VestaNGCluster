package ru.foric27.cluster

import androidx.preference.PreferenceDataStore

/**
 * AndroidX Preference bridge over the existing RuntimeConfig facade.
 *
 * The current developer screen remains custom ViewBinding, but small future
 * PreferenceFragmentCompat sections can use this bridge without reintroducing
 * SharedPreferences writes.
 */
internal class RuntimeConfigPreferenceDataStore(
    private val saveString: (String, String) -> Boolean,
    private val readString: (String, String?) -> String?,
) : PreferenceDataStore() {

    override fun putString(key: String, value: String?) {
        if (value != null) {
            saveString(key, value)
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        saveString(key, value.toString())
    }

    override fun getString(key: String, defValue: String?): String? {
        return readString(key, defValue)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return RuntimeConfig.parseBooleanValue(readString(key, defValue.toString()), defValue)
    }
}
