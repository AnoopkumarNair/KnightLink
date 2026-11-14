package knightlink.core.sim

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Service 09 (vehicle information) as served by the simulated ECUs. */
class VehicleInfoTest {
    private fun respond(ecu: VirtualEcu, request: String): ByteArray? = ecu.respond(request.hexToBytes())

    @Test
    fun infoTypeBitmapIsDerivedFromStoredService09Entries() {
        val ecu = VirtualEcu(0x7E0, 0x7E8).apply {
            set("0902", "01" + "ABC".encodeToByteArray().toHex())
            set("0904", "01" + "00".repeat(16))
            set("090A", "01" + "00".repeat(20))
        }
        // 02 -> bit 30, 04 -> bit 28, 0A -> bit 22; nothing beyond 0x09 range, so the "next range" bit stays clear.
        // 0x40000000 | 0x10000000 | 0x00400000 = 0x50400000
        assertEquals("490050400000", respond(ecu, "0900")!!.toHex())
    }

    @Test
    fun ecuWithoutAnyService09DataStaysSilentOnTheBitmap() {
        val ecu = VirtualEcu(0x7E1, 0x7E9).apply { set("0131", "0EEE") }
        assertNull(respond(ecu, "0900"))
        assertNull(respond(ecu, "0902"))
    }

    @Test
    fun service01BitmapIsUnaffectedByService09Data() {
        val ecu = VirtualEcu(0x7E0, 0x7E8).apply {
            set("0105", "6B")
            set("0902", "01" + "ABC".encodeToByteArray().toHex())
        }
        assertEquals("410008000000", respond(ecu, "0100")!!.toHex())
    }

    @Test
    fun nameAndCalibrationIdArePaddedWithNulsToTheirFixedWidths() {
        val file = SimulationFile.parse(
            """
            {"schemaVersion":1,"id":"sim/x","title":"t","definition":"d","ecus":[
              {"id":"a","requestId":"7E0","responseId":"7E8","name":"ABC","calibrationId":"CAL1"}
            ]}
            """.trimIndent(),
        )
        val ecu = file.buildVehicle().ecu(0x7E0)
        assertEquals("490A01" + "414243" + "00".repeat(17), respond(ecu, "090A")!!.toHex())
        assertEquals("490401" + "43414C31" + "00".repeat(12), respond(ecu, "0904")!!.toHex())
        assertNotNull(respond(ecu, "0900"))
    }

    @Test
    fun anOverlongNameOrCalibrationIdIsRejected() {
        fun file(extra: String) = SimulationFile.parse(
            """{"schemaVersion":1,"id":"sim/x","title":"t","definition":"d","ecus":[{"id":"a","requestId":"7E0","responseId":"7E8",$extra}]}""",
        )
        assertFailsWith<IllegalArgumentException> { file(""""name":"${"N".repeat(21)}"""").buildVehicle() }
        assertFailsWith<IllegalArgumentException> { file(""""calibrationId":"${"C".repeat(17)}"""").buildVehicle() }
        // Exactly at the limit is fine.
        file(""""name":"${"N".repeat(20)}","calibrationId":"${"C".repeat(16)}"""").buildVehicle()
    }

    @Test
    fun nonAsciiNamesAreRejectedBecauseTheyCannotBeServedAsAscii() {
        val file = SimulationFile.parse(
            """{"schemaVersion":1,"id":"sim/x","title":"t","definition":"d","ecus":[{"id":"a","requestId":"7E0","responseId":"7E8","name":"ÉCM"}]}""",
        )
        assertFailsWith<IllegalArgumentException> { file.buildVehicle() }
    }
}
