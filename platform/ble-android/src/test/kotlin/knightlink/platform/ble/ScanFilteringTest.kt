package knightlink.platform.ble

import knightlink.core.transport.ble.BleSerialProfile
import knightlink.core.transport.ble.BluetoothUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanFilteringTest {
    // Short made-up identifiers; real ones live in definition files.
    private val profile = BleSerialProfile("ab01", "ab02", "ab02", ignoreServiceUuids = listOf("ab03"))
    private val matcher = AdapterScanMatcher(profile, listOf("Some-Name", "  Other "))

    @Test
    fun anAdvertisedNameAloneIsEnough() {
        assertTrue(matcher.matches("Some-Name", emptyList()))
    }

    @Test
    fun nameMatchingIgnoresCaseAndSurroundingSpaces() {
        assertTrue(matcher.matches("some-NAME ", emptyList()))
        assertTrue(matcher.matches("other", emptyList()))
    }

    @Test
    fun theAdvertisedServiceAloneIsEnoughInAnyUuidForm() {
        assertTrue(matcher.matches(null, listOf(BluetoothUuid.fromShort(0xab01).uppercase())))
        assertTrue(matcher.matches("Unrelated", listOf("AB01")))
    }

    @Test
    fun otherDevicesAreNotMatched() {
        assertFalse(matcher.matches(null, emptyList()))
        assertFalse(matcher.matches("Some-Name-Extra", listOf(BluetoothUuid.fromShort(0xab03))))
        // Advertising only the ignored service is not a match.
        assertFalse(matcher.matches(null, listOf("ab03")))
    }

    @Test
    fun blankExpectedNamesNeverMatchAnEmptyAdvertisedName() {
        val blank = AdapterScanMatcher(profile, listOf("", "  "))

        assertFalse(blank.matches("", emptyList()))
        assertFalse(blank.matches(null, emptyList()))
    }

    @Test
    fun theFirstSightingOfAnAddressIsReported() {
        val result = ScanDeduplicator().accept("A", "Name", -60)

        assertEquals(DiscoveredAdapter("A", "Name", -60), result)
    }

    @Test
    fun repeatedAdvertisementsWithSimilarSignalAreSwallowed() {
        val dedup = ScanDeduplicator(rssiStep = 5)
        dedup.accept("A", "Name", -60)

        assertNull(dedup.accept("A", "Name", -60))
        assertNull(dedup.accept("A", "Name", -63))
        assertNull(dedup.accept("A", "Name", -64))
    }

    @Test
    fun aNoticeableSignalChangeIsReportedWithTheNewRssi() {
        val dedup = ScanDeduplicator(rssiStep = 5)
        dedup.accept("A", "Name", -60)

        assertEquals(DiscoveredAdapter("A", "Name", -50), dedup.accept("A", "Name", -50))
        // The step is measured from what was last reported, so creeping drift is eventually reported too.
        assertNull(dedup.accept("A", "Name", -53))
        assertEquals(-58, dedup.accept("A", "Name", -58)?.rssi)
    }

    @Test
    fun learningTheNameLaterIsReportedEvenWithTheSameSignal() {
        val dedup = ScanDeduplicator()
        dedup.accept("A", null, -60)

        assertEquals(DiscoveredAdapter("A", "Name", -60), dedup.accept("A", "Name", -60))
    }

    @Test
    fun aLaterAdvertisementWithoutANameKeepsTheKnownName() {
        val dedup = ScanDeduplicator()
        dedup.accept("A", "Name", -60)

        assertEquals(DiscoveredAdapter("A", "Name", -40), dedup.accept("A", null, -40))
    }

    @Test
    fun addressesAreTrackedIndependently() {
        val dedup = ScanDeduplicator()
        dedup.accept("A", "Name", -60)

        assertEquals("B", dedup.accept("B", "Name", -60)?.address)
        assertNull(dedup.accept("A", "Name", -60))
    }
}
