package knightlink.core.scan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real file in definitions/discovery: valid, and says what the plan promised. */
class DidSweepFileTest {
    private val file = File(System.getProperty("knightlink.repoRoot"), "definitions/discovery/did-sweep.json")

    private val plan get() = DidSweepPlan.parse(file.readText())

    @Test
    fun theSweepFileIsValidAndItsIdMatchesItsPath() {
        assertTrue(file.isFile, "expected $file")
        assertEquals("discovery/did-sweep", plan.id)
    }

    @Test
    fun theSweepDoesNotAskForMoreThanTwentyRequestsASecond() {
        assertTrue(plan.requestsPerSecond <= 20.0)
    }

    @Test
    fun theRangesAreTheOnesTheScanWasBriefedWith() {
        assertEquals(
            listOf("0000-00FF", "0100-01FF", "1E00-1EFF", "2000-20FF", "D000-D0FF", "DD00-DDFF", "E000-E0FF", "F400-F4FF"),
            plan.ranges.map { "${it.from}-${it.to}" },
        )
    }

    @Test
    fun theControlUnitDiscoveryCoversTheElevenBitDiagnosticIdsAndSkipsTheFunctionalAddress() {
        val ids = plan.controlUnitIds.ids()
        assertEquals(0x700, ids.first())
        assertEquals(0x7F7, ids.last())
        assertTrue(0x7DF !in ids)
        assertTrue(ids.all { it in 0x700..0x7F7 })
    }

    @Test
    fun theTargetsAreTheGearboxEngineAndExhaustTreatmentUnitsWithTheirCandidates() {
        val targets = plan.targets.associateBy { it.requestId }
        assertEquals(listOf("7E1", "7E0", "7E4"), plan.targets.map { it.requestId })
        assertEquals(
            listOf("1E12", "1E1F", "1E65", "1E66", "1E68", "1E69", "1E7E", "201C", "01A0", "01A4", "21A0"),
            targets.getValue("7E1").candidates,
        )
        assertEquals(
            listOf("03F3", "03E6", "0415", "1310", "6310", "E001", "E011", "E0F1", "ED02", "ED05"),
            targets.getValue("7E0").candidates,
        )
        assertEquals(emptyList(), targets.getValue("7E4").candidates)
    }

    @Test
    fun onlyTheFirstTargetIsPolledInFullWhileDriving() {
        val targets = plan.targets.associateBy { it.requestId }
        assertEquals(null, targets.getValue("7E1").pollLimit)
        assertEquals(20, targets.getValue("7E0").pollLimit)
        assertEquals(8, targets.getValue("7E0").pollMaxBytes)
        assertEquals(20, targets.getValue("7E4").pollLimit)
    }

    @Test
    fun everyIdentifierIsAskedOnceAndTheSweepOfAUnitIsAboutTwoThousandRequests() {
        for (target in plan.targets) {
            val identifiers = plan.identifiersFor(target)
            assertEquals(identifiers.size, identifiers.toSet().size)
            assertTrue(identifiers.size in 2048..2100, "${target.requestId}: ${identifiers.size}")
        }
    }
}
