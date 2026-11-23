package knightlink.core.vehicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VehicleIdentityTest {
    private val table = WmiTable.parse(
        """{ "schemaVersion": 1, "entries": [
            { "wmi": "MA1", "make": "Mahindra", "verified": true, "source": "a source" },
            { "wmi": "ZZ9", "make": "Somebody" } ] }""",
    )

    @Test
    fun aVinsManufacturerCodeNamesTheMakeAndAnUnlistedOneIsUnknown() {
        assertEquals("Mahindra", table.makeOf("ma1ABCDEFGHJKLMNP"))
        assertEquals(true, table.lookup("MA1XXXXXXXXXXXXXX")?.verified)
        assertEquals(false, table.lookup("ZZ9XXXXXXXXXXXXXX")?.verified)
        assertEquals(UNKNOWN_MAKE, table.makeOf("QQQXXXXXXXXXXXXXX"))
        assertEquals(UNKNOWN_MAKE, table.makeOf(null))
        assertEquals(UNKNOWN_MAKE, table.makeOf("MA"))
    }

    @Test
    fun aVerifiedEntryMustNameItsSourceAndACodeIsListedOnce() {
        assertFailsWith<IllegalArgumentException> {
            WmiTable.parse("""{ "schemaVersion": 1, "entries": [ { "wmi": "MA1", "make": "X", "verified": true } ] }""")
        }
        assertFailsWith<IllegalArgumentException> {
            WmiTable.parse("""{ "schemaVersion": 1, "entries": [ { "wmi": "MA1", "make": "X" }, { "wmi": "MA1", "make": "Y" } ] }""")
        }
        assertFailsWith<IllegalArgumentException> { WmiTable.parse("""{ "schemaVersion": 1, "entries": [ { "wmi": "ma", "make": "X" } ] }""") }
    }

    @Test
    fun theModelYearComesFromTheTenthCharacterAndTheLatestYearThatFits() {
        fun vin(code: Char) = "MA1XXXXXX" + code + "XXXXXXX"
        assertEquals(2026, ModelYear.fromVin(vin('T'), latestYear = 2027))
        assertEquals(2027, ModelYear.fromVin(vin('V'), latestYear = 2027))
        assertEquals(2020, ModelYear.fromVin(vin('L'), latestYear = 2027))
        assertEquals(2009, ModelYear.fromVin(vin('9'), latestYear = 2027))
        assertEquals(2031, ModelYear.fromVin(vin('1'), latestYear = 2035))
        assertNull(ModelYear.fromVin(vin('U'), latestYear = 2027))
        assertNull(ModelYear.fromVin(vin('*'), latestYear = 2027))
        assertNull(ModelYear.fromVin("MA1", latestYear = 2027))
        assertNull(ModelYear.fromVin(null, latestYear = 2027))
    }

    @Test
    fun theProtocolNumberTheAdapterReportsNamesTheProtocol() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("6"))
        assertEquals(ObdProtocol.CAN_29_500, ObdProtocol.fromDpn(" a7 "))
        assertEquals(ObdProtocol.CAN_11_250, ObdProtocol.fromDpn("8"))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromDpn("A"))
        assertNull(ObdProtocol.fromDpn("0"))
        assertNull(ObdProtocol.fromDpn("A0"))
        assertNull(ObdProtocol.fromDpn("?"))
        assertNull(ObdProtocol.fromDpn(null))
        assertEquals(listOf(ObdProtocol.CAN_11_500, ObdProtocol.CAN_11_250), ObdProtocol.entries.filter { it.isReadable })
        assertEquals("ATSP6", ObdProtocol.CAN_11_500.selectCommand)
        assertEquals(
            listOf(ObdProtocol.CAN_11_500, ObdProtocol.CAN_11_250, ObdProtocol.CAN_29_500, ObdProtocol.CAN_29_250),
            ObdProtocol.EXPLICIT_ORDER.take(4),
        )
    }
}
