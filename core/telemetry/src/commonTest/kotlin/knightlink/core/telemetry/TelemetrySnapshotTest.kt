package knightlink.core.telemetry

import knightlink.core.signals.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelemetrySnapshotTest {
    private fun reading(signal: String, value: Double, ecu: String? = "ecm") = Reading(ecu, SignalValue(signal, value, "u"))

    @Test
    fun startsEmpty() {
        assertTrue(TelemetrySnapshot.EMPTY.isEmpty())
        assertNull(TelemetrySnapshot.EMPTY.value("engine.rpm"))
    }

    @Test
    fun withReturnsANewSnapshotAndLeavesTheOldOneUntouched() {
        val before = TelemetrySnapshot.EMPTY
        val after = before.with(listOf(reading("engine.rpm", 799.0)), 2.seconds)
        assertTrue(before.isEmpty())
        assertEquals(799.0, after.value("engine.rpm"))
        assertEquals(2.seconds, after.reading("engine.rpm")!!.at)
    }

    @Test
    fun aNewReadingReplacesTheOldOneForTheSameEcuAndSignal() {
        val first = TelemetrySnapshot.EMPTY.with(listOf(reading("engine.rpm", 799.0)), 1.seconds)
        val second = first.with(listOf(reading("engine.rpm", 1500.0)), 3.seconds)
        assertEquals(1500.0, second.value("engine.rpm"))
        assertEquals(3.seconds, second.reading("engine.rpm")!!.at)
        assertEquals(1, second.size)
        assertEquals(799.0, first.value("engine.rpm"))
    }

    @Test
    fun readingsFromOtherSignalsAreKept() {
        val snapshot = TelemetrySnapshot.EMPTY
            .with(listOf(reading("engine.rpm", 799.0)), 1.seconds)
            .with(listOf(reading("dpf.soot_pct", 89.0)), 2.seconds)
        assertEquals(799.0, snapshot.value("engine.rpm"))
        assertEquals(89.0, snapshot.value("dpf.soot_pct"))
        assertEquals(1.seconds, snapshot.reading("engine.rpm")!!.at)
    }

    @Test
    fun sameSignalFromTwoEcusIsKeptApartAndAskedForByEcu() {
        val snapshot = TelemetrySnapshot.EMPTY
            .with(listOf(reading("engine.rpm", 799.0, ecu = "ecm")), 1.seconds)
            .with(listOf(reading("engine.rpm", 801.0, ecu = "rdcm")), 2.seconds)
        assertEquals(799.0, snapshot.value("engine.rpm", ecu = "ecm"))
        assertEquals(801.0, snapshot.value("engine.rpm", ecu = "rdcm"))
        assertNull(snapshot.value("engine.rpm", ecu = "tcm"))
        assertEquals(2, snapshot.size)
    }

    @Test
    fun withoutAnEcuTheMostRecentReadingWins() {
        val snapshot = TelemetrySnapshot.EMPTY
            .with(listOf(reading("engine.rpm", 801.0, ecu = "rdcm")), 1.seconds)
            .with(listOf(reading("engine.rpm", 799.0, ecu = "ecm")), 2.seconds)
        assertEquals(799.0, snapshot.value("engine.rpm"))
    }

    @Test
    fun unattributedReadingsAreFoundWithoutAnEcu() {
        val snapshot = TelemetrySnapshot.EMPTY.with(listOf(reading("engine.rpm", 799.0, ecu = null)), 1.seconds)
        assertEquals(799.0, snapshot.value("engine.rpm"))
    }

    @Test
    fun addingNothingReturnsTheSameInstance() {
        val snapshot = TelemetrySnapshot.EMPTY.with(listOf(reading("engine.rpm", 799.0)), 1.seconds)
        assertSame(snapshot, snapshot.with(emptyList(), 5.seconds))
    }

    @Test
    fun snapshotsCompareByContentSoAStateFlowOnlyEmitsRealChanges() {
        val a = TelemetrySnapshot.EMPTY.with(listOf(reading("engine.rpm", 799.0)), 1.seconds)
        val b = TelemetrySnapshot.EMPTY.with(listOf(reading("engine.rpm", 799.0)), 1.seconds)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.with(listOf(reading("engine.rpm", 799.0)), 2.seconds))
    }

    @Test
    fun outOfRangeReadingsAreKeptWithTheirFlag() {
        val value = Reading("ecm", SignalValue("engine.rpm", 99999.0, "rpm", inRange = false))
        val stored = TelemetrySnapshot.EMPTY.with(listOf(value), 1.seconds).reading("engine.rpm")!!
        assertEquals(false, stored.reading.signal.inRange)
    }
}
