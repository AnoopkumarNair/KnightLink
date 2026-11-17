package knightlink.app.ble

/** The adapter the user connected to last. Never anything about the vehicle. */
data class LastAdapter(val address: String, val name: String?)

/** A tiny string key-value store; SharedPreferences in the app, a map in tests. A null value removes the key. */
interface StringStore {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

/** Remembers the last adapter in [store]. An address without a usable value is never returned, and a stale name is never kept. */
class LastAdapterStore(private val store: StringStore) {
    fun load(): LastAdapter? {
        val address = store.get(KEY_ADDRESS)?.takeIf { it.isNotBlank() } ?: return null
        return LastAdapter(address, store.get(KEY_NAME)?.takeIf { it.isNotBlank() })
    }

    /** Ignored when [adapter] has no address. Saving an adapter without a name removes the name of the previous one. */
    fun save(adapter: LastAdapter) {
        if (adapter.address.isBlank()) return
        store.put(KEY_ADDRESS, adapter.address)
        store.put(KEY_NAME, adapter.name?.takeIf { it.isNotBlank() })
    }

    fun clear() {
        store.put(KEY_ADDRESS, null)
        store.put(KEY_NAME, null)
    }

    private companion object {
        const val KEY_ADDRESS = "last_adapter_address"
        const val KEY_NAME = "last_adapter_name"
    }
}
