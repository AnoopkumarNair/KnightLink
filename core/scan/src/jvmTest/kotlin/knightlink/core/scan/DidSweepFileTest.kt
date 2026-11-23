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

    @Test
    fun theHarrierSeedIsAValidScanResultOfTheIdentifiersTheFirstScanFound() {
        val seedFile = File(System.getProperty("knightlink.repoRoot"), "definitions/discovery/seed/tata/harrier-2020-kryotec-at.json")
        val seed = ScanResult.fromJson(seedFile.readText())
        assertEquals(ScanOutcome.COMPLETED, seed.outcome)
        val targets = plan.targets.map { it.requestId.uppercase() }.toSet()
        assertTrue(seed.sweeps.all { it.requestId.uppercase() in targets }, "a seeded unit that the plan does not sweep would never be polled")
        val answers = seed.sweeps.flatMap { it.positive }
        assertTrue(answers.isNotEmpty())
        assertTrue(answers.all { Regex("[0-9A-F]{4}").matches(it.did) }, "identifiers are four hex digits")
        assertTrue(answers.all { a -> a.hex.all { it == '0' } }, "values are zeroed: only their length is kept")
        assertTrue(seed.ecus.isEmpty() && seed.probeEcus.isEmpty(), "no identities in a seed")
    }

    @Test
    fun theHarrierCandidateFileIsValidAndAsksTheBodyUnits() {
        val file = File(System.getProperty("knightlink.repoRoot"), "definitions/discovery/candidates/tata/harrier-2020-kryotec-at.json")
        val checks = CandidateChecks.parse(file.readText())
        assertEquals(listOf("700", "701", "703", "705", "750"), checks.checks.map { it.requestId })
        assertTrue(checks.checks.all { it.unprobed }, "none of the body units is in the OBD probe")
        assertTrue(checks.checks.all { check -> (0xF180..0xF1FF).all { it in check.identifiers() } }, "F180-F1FF on every unit")
        val cluster = checks.checks.single { it.requestId == "700" }.identifiers()
        assertTrue(0xF190 in cluster && 0x0100 in cluster && 0x01FF in cluster && 0xDD00 in cluster && 0xDDFF in cluster)
        assertEquals(0x80 * 5 + 0x100 * 2, checks.checks.sumOf { it.identifiers().size })
        assertTrue(checks.checks.sumOf { it.identifiers().size } <= CandidateChecks.MAX_IDENTIFIERS)
        assertEquals(listOf("MAT"), checks.appliesTo, "only for the Tata manufacturer code, as the vehicle definition's wmi")
        assertTrue(checks.appliesTo("MAT123456789") && !checks.appliesTo("SIM123") && !checks.appliesTo(null))
        assertEquals(0x708, checks.checks.first().responseCanId)
        assertEquals(listOf(128, 256, 256), checks.checks.first().groups().map { it.size })
    }

    @Test
    fun aCandidateFileThatAsksForTooMuchOrIsMalformedIsRefused() {
        val big = """{"schemaVersion":1,"id":"x","checks":[{"requestId":"7E1","label":"g","ranges":[{"from":"0000","to":"0FFF"}]}]}"""
        assertTrue(runCatching { CandidateChecks.parse(big) }.isFailure)
        val badId = """{"schemaVersion":1,"id":"x","checks":[{"requestId":"7E100","label":"g","dids":["1F12"]}]}"""
        assertTrue(runCatching { CandidateChecks.parse(badId) }.isFailure)
        val broadcastId = """{"schemaVersion":1,"id":"x","checks":[{"requestId":"1A0","label":"g","dids":["1F12"]}]}"""
        assertTrue(runCatching { CandidateChecks.parse(broadcastId) }.isFailure, "a request id outside 700-7FF is the car's own broadcast")
        val badPrefix = """{"schemaVersion":1,"id":"x","appliesTo":["M A"],"checks":[]}"""
        assertTrue(runCatching { CandidateChecks.parse(badPrefix) }.isFailure)
        val unknownKey = """{"schemaVersion":1,"id":"x","checks":[],"service":"2E"}"""
        assertTrue(runCatching { CandidateChecks.parse(unknownKey) }.isFailure, "a file cannot name a service")
    }
}
