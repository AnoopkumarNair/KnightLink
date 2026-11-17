package knightlink.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DidSweepPlanTest {
    private fun plan(
        pace: String = "20",
        ids: String = """{ "from": "700", "to": "7F7", "skip": ["7DF"] }""",
        ranges: String = """[ { "from": "0000", "to": "00FF" }, { "from": "1E00", "to": "1EFF" } ]""",
        targets: String = """[ { "requestId": "7E1", "label": "gearbox", "candidates": ["1E12", "0105"] } ]""",
        extra: String = "",
        schema: Int = 1,
    ) = """{ "schemaVersion": $schema, "id": "discovery/test", "title": "t", "requestsPerSecond": $pace, "controlUnitIds": $ids,
        "ranges": $ranges, "targets": $targets $extra }"""

    @Test
    fun aValidPlanIsReadAndItsIdentifiersComeCandidatesFirstEachOnce() {
        val plan = DidSweepPlan.parse(plan())
        val target = plan.targets.single()
        val identifiers = plan.identifiersFor(target)
        assertEquals(listOf(0x1E12, 0x0105), identifiers.take(2))
        assertEquals(2 + 256 + 256 - 1, identifiers.size, "1E12 is in a range and is asked once; 0105 is outside")
        assertEquals(identifiers.size, identifiers.toSet().size)
    }

    @Test
    fun theChunksAreTheCandidatesAndThenEachRangeWithoutWhatWasAskedAlready() {
        val plan = DidSweepPlan.parse(plan())
        val chunks = plan.chunksFor(plan.targets.single())
        assertEquals(listOf(2, 256, 255), chunks.map { it.size })
        assertEquals(0x1E12, chunks[0][0])
        assertTrue(0x1E12 !in chunks[2])
    }

    @Test
    fun theControlUnitIdsSkipWhatTheFileSkips() {
        val ids = DidSweepPlan.parse(plan()).controlUnitIds.ids()
        assertEquals(0x700, ids.first())
        assertEquals(0x7F7, ids.last())
        assertTrue(0x7DF !in ids)
        assertEquals(0xF8 - 1, ids.size)
    }

    @Test
    fun aTargetMayCarryItsOwnRanges() {
        val plan = DidSweepPlan.parse(
            plan(targets = """[ { "requestId": "7E1", "label": "g", "ranges": [ { "from": "F400", "to": "F40F" } ] } ]"""),
        )
        assertEquals((0xF400..0xF40F).toList(), plan.identifiersFor(plan.targets.single()))
    }

    @Test
    fun theWrongThingsAreRefusedWithAMessageThatSaysWhich() {
        fun refused(text: String, needle: String) {
            val error = assertFailsWith<IllegalArgumentException> { DidSweepPlan.parse(text) }
            assertTrue(needle in error.message.orEmpty(), "'$needle' not in '${error.message}'")
        }
        refused(plan(pace = "21"), "requestsPerSecond")
        refused(plan(pace = "0"), "requestsPerSecond")
        refused(plan(schema = 2), "schema 2")
        refused(plan(ranges = """[ { "from": "00FF", "to": "0000" } ]"""), "ends before it starts")
        refused(plan(ranges = """[ { "from": "0000", "to": "00FF" }, { "from": "0080", "to": "0180" } ]"""), "overlapping")
        refused(plan(ranges = """[ { "from": "00", "to": "FF" } ]"""), "4 upper-case hex digits")
        refused(plan(ranges = """[ { "from": "0000", "to": "FFFF" } ]"""), "longer than")
        refused(plan(ranges = "[]"), "empty")
        refused(plan(targets = "[]"), "no targets")
        refused(plan(targets = """[ { "requestId": "7e1", "label": "g" } ]"""), "upper-case")
        refused(plan(targets = """[ { "requestId": "7E1", "label": "g" }, { "requestId": "7E1", "label": "h" } ]"""), "twice")
        refused(plan(targets = """[ { "requestId": "800", "label": "g" } ]"""), "11-bit")
        refused(plan(targets = """[ { "requestId": "7E1", "label": "g", "candidates": ["1E12", "1E12"] } ]"""), "twice")
        refused(plan(targets = """[ { "requestId": "7E1", "label": "g", "candidates": ["12"] } ]"""), "4 upper-case hex digits")
        refused(plan(ids = """{ "from": "700", "to": "7F7", "skip": ["800"] }"""), "outside the range")
        refused(plan(extra = """, "service": "2E" """), "service")
    }

    @Test
    fun aFileCannotNameAServiceOnlyIdentifiers() {
        // The schema has no field for a service: a key that would add one is an unknown key, which is refused.
        assertFailsWith<IllegalArgumentException> { DidSweepPlan.parse(plan(extra = """, "write": ["2EF19000"] """)) }
    }
}
