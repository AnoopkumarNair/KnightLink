package knightlink.core.scan

import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.vehicle.EcuReport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaultReaderTest {
    private val sent = ArrayList<String>()

    private fun unit(pids: List<String>) = EcuReport("7E0", "7E8", null, emptyList(), pids, null)

    private fun response(vararg messages: DiagnosticResponse) = ElmResponse(messages.toList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun positive(service: Int, vararg data: Int) = DiagnosticResponse.Positive(0x7E8, service, data.map { it.toByte() }.toByteArray())

    private fun link(answer: (String) -> ElmResponse) = object : DiagnosticLink {
        override suspend fun command(text: String): List<String> = emptyList()

        override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
            sent += payload.toHex()
            return answer(payload.toHex())
        }
    }

    @Test
    fun codesAreSortedByTheListTheyCameFromAndAReadinessIsBuilt() = runTest {
        val link = link { request ->
            when (request) {
                "0101" -> response(positive(1, 0x01, 0x81, 0x07, 0xE5, 0x00))
                "03" -> response(positive(3, 0x02, 0x01, 0x33, 0x03, 0x01))
                "07" -> response(positive(7, 0x01, 0x03, 0x02))
                "0A" -> response(positive(0x0A, 0x00))
                else -> response(DiagnosticResponse.Negative(0x7E8, 0x19, 0x12))
            }
        }
        val report = FaultReader(link).read(listOf(unit(listOf("01"))))
        assertEquals(
            listOf("P0133" to FaultKind.CONFIRMED, "P0301" to FaultKind.CONFIRMED, "P0302" to FaultKind.PENDING),
            report.faults.map { it.code to it.kind },
        )
        assertEquals(true, report.readiness?.milOn)
        assertEquals(1, report.readiness?.storedCount)
        assertTrue(report.notAvailable.any { "service 19" in it })
        // Each request is asked once: nothing is retried, and nothing that changes the car is sent.
        assertEquals(listOf("0101", "03", "07", "0A", "1902FF"), sent)
    }

    @Test
    fun aRefusalIsFinalAndSilenceIsNotAnAnswer() = runTest {
        val link = link { request ->
            if (request == "03") response(DiagnosticResponse.Negative(0x7E8, 3, 0x33)) else response()
        }
        val report = FaultReader(link).read(listOf(unit(emptyList())))
        assertTrue(report.faults.isEmpty())
        assertEquals(null, report.readiness)
        assertEquals(1, sent.count { it == "03" })
        assertTrue(report.notAvailable.any { "refused with 33" in it })
    }

    @Test
    fun descriptionsComeFromTheDictionaryAndManufacturerCodesGetNone() {
        val dictionary = FaultCodeDictionary(mapOf("P0133" to "O2 sensor slow"))
        val report = FaultReport(
            listOf(FaultEntry("P0133", FaultKind.CONFIRMED, "7E0"), FaultEntry("P1234", FaultKind.PENDING, "7E0")),
            null,
            listOf("7E0"),
        ).described(dictionary)
        assertEquals(listOf("O2 sensor slow", null), report.faults.map { it.description })
    }
}
