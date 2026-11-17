package knightlink.platform.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BleAdvertisementListenerTest {
    @Test
    fun aCompanyIdentifierIsListenedForAsWrittenAndWithItsBytesSwapped() {
        assertEquals(listOf(0x12AB, 0xAB12), BleAdvertisementListener.identifiersFor("12ab").toList())
    }

    @Test
    fun anIdentifierThatReadsTheSameBothWaysIsListenedForOnce() {
        assertEquals(setOf(0x3434), BleAdvertisementListener.identifiersFor("3434"))
    }

    @Test
    fun anIdentifierThatIsNotFourHexDigitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { BleAdvertisementListener.identifiersFor("123") }
        assertFailsWith<IllegalArgumentException> { BleAdvertisementListener.identifiersFor("12G4") }
        assertFailsWith<IllegalArgumentException> { BleAdvertisementListener.identifiersFor("0x12") }
    }
}
