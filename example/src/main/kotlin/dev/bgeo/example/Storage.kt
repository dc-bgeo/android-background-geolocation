package dev.bgeo.example

import android.content.SharedPreferences

/**
 * Narrow key-value storage `ConfigStore` persists through. `SharedPreferences`
 * itself is an `android.jar` interface and is stubbed in unit tests, so this
 * seam exists to let tests use a plain in-memory fake instead.
 */
interface Storage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStorage(private val prefs: SharedPreferences) : Storage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
