package knightlink.app.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastAdapterStoreTest {
    private class MapStore(val values: MutableMap<String, String> = HashMap()) : StringStore {
        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun nothingIsRememberedAtFirst() {
        assertNull(LastAdapterStore(MapStore()).load())
    }

    @Test
    fun whatWasSavedIsLoadedAgainEvenByANewStoreOverTheSameBacking() {
        val backing = MapStore()
        LastAdapterStore(backing).save(LastAdapter("addr-1", "Some adapter"))

        assertEquals(LastAdapter("addr-1", "Some adapter"), LastAdapterStore(backing).load())
    }

    @Test
    fun anAdapterWithoutANameIsRemembered() {
        val store = LastAdapterStore(MapStore())

        store.save(LastAdapter("addr-1", null))

        assertEquals(LastAdapter("addr-1", null), store.load())
    }

    @Test
    fun theNameOfAnEarlierAdapterIsNotKeptWhenALaterOneHasNone() {
        val store = LastAdapterStore(MapStore())
        store.save(LastAdapter("addr-1", "Old name"))

        store.save(LastAdapter("addr-2", null))

        assertEquals(LastAdapter("addr-2", null), store.load())
    }

    @Test
    fun theLatestChoiceWins() {
        val store = LastAdapterStore(MapStore())
        store.save(LastAdapter("addr-1", "One"))
        store.save(LastAdapter("addr-2", "Two"))

        assertEquals(LastAdapter("addr-2", "Two"), store.load())
    }

    @Test
    fun aBlankNameCountsAsNoName() {
        val store = LastAdapterStore(MapStore())

        store.save(LastAdapter("addr-1", "   "))

        assertNull(store.load()?.name)
    }

    @Test
    fun aBlankAddressIsNeverSavedAndKeepsWhatWasThere() {
        val store = LastAdapterStore(MapStore())
        store.save(LastAdapter("addr-1", "One"))

        store.save(LastAdapter("  ", "Two"))

        assertEquals(LastAdapter("addr-1", "One"), store.load())
    }

    @Test
    fun aBlankStoredAddressIsNotAnAdapter() {
        val backing = MapStore(hashMapOf("last_adapter_address" to " ", "last_adapter_name" to "Name"))

        assertNull(LastAdapterStore(backing).load())
    }

    @Test
    fun clearForgetsEverything() {
        val backing = MapStore()
        val store = LastAdapterStore(backing)
        store.save(LastAdapter("addr-1", "One"))

        store.clear()

        assertNull(store.load())
        assertTrue(backing.values.isEmpty())
    }

    @Test
    fun onlyTheAddressAndTheNameAreStored() {
        val backing = MapStore()

        LastAdapterStore(backing).save(LastAdapter("addr-1", "One"))

        assertEquals(2, backing.values.size)
    }
}
