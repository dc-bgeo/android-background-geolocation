package dev.bgeo.example

/** In-memory [Storage] fake — the real implementation wraps `SharedPreferences`, which is stubbed in unit tests. */
class InMemoryStorage : Storage {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
