package knightlink.app.ble

import knightlink.core.transport.TransportException
import knightlink.platform.ble.DiscoveredAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ScanResultsTest {
    private fun found(address: String, name: String? = null, rssi: Int = -60) = DiscoveredAdapter(address, name, rssi)

    @Test
    fun anAddressAppearsOnceWhateverHowOftenItIsSeen() {
        val results = ScanResults().with(found("a", "One", -70)).with(found("a", "One", -50)).with(found("a", "One", -60))

        assertEquals(listOf(found("a", "One", -60)), results.adapters)
    }

    @Test
    fun theStrongestSignalComesFirst() {
        val results = ScanResults().with(found("a", rssi = -80)).with(found("b", rssi = -40)).with(found("c", rssi = -60))

        assertEquals(listOf("b", "c", "a"), results.adapters.map { it.address })
    }

    @Test
    fun theOrderFollowsASignalThatChanges() {
        val results = ScanResults().with(found("a", rssi = -80)).with(found("b", rssi = -60)).with(found("a", rssi = -30))

        assertEquals(listOf("a", "b"), results.adapters.map { it.address })
    }

    @Test
    fun equalSignalsAreOrderedByNameThenAddressSoTheListIsStable() {
        val first = ScanResults().with(found("z", "Beta")).with(found("y", "Alpha")).with(found("x", "Alpha"))
        val second = ScanResults().with(found("x", "Alpha")).with(found("y", "Alpha")).with(found("z", "Beta"))

        assertEquals(listOf("x", "y", "z"), first.adapters.map { it.address })
        assertEquals(first, second)
    }

    @Test
    fun aNameLearnedLaterIsAdded() {
        val results = ScanResults().with(found("a", null, -60)).with(found("a", "Named", -60))

        assertEquals("Named", results.adapters.single().name)
    }

    @Test
    fun aNameOnceKnownIsKeptWhenALaterResultHasNone() {
        val results = ScanResults().with(found("a", "Named", -60)).with(found("a", null, -55)).with(found("a", "  ", -50))

        val only = results.adapters.single()
        assertEquals("Named", only.name)
        assertEquals(-50, only.rssi)
    }

    @Test
    fun anUnnamedAdapterStaysUnnamed() {
        assertEquals(null, ScanResults().with(found("a", null)).adapters.single().name)
    }

    @Test
    fun resultsAreNotChangedByMerging() {
        val before = ScanResults().with(found("a"))

        before.with(found("b"))

        assertEquals(1, before.adapters.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PickerStatesTest {
    private fun found(address: String, rssi: Int = -60) = DiscoveredAdapter(address, "Name", rssi)

    /** Emits [items] and then keeps scanning for ever, as a real scan does. */
    private fun endless(vararg items: DiscoveredAdapter): Flow<DiscoveredAdapter> = flow {
        items.forEach { emit(it) }
        awaitCancellation()
    }

    @Test
    fun updatesWhileScanningThenFinishesAfterTheScanTime() = runTest {
        val states = pickerStates(endless(found("a", -70), found("b", -50)), 20.seconds).toList()

        assertEquals(20_000L, currentTime)
        assertEquals(4, states.size)
        assertEquals(PickerState.Scanning(ScanResults()), states[0])
        assertEquals(listOf("a"), states[1].results.adapters.map { it.address })
        assertEquals(listOf("b", "a"), states[2].results.adapters.map { it.address })
        assertEquals(PickerState.Finished(states[2].results), states[3])
    }

    @Test
    fun anEmptyScanFinishesWithNothing() = runTest {
        val last = pickerStates(endless(), 20.seconds).toList().last()

        assertEquals(PickerState.Finished(ScanResults()), last)
    }

    @Test
    fun aScanThatEndsOnItsOwnFinishesEarly() = runTest {
        val states = pickerStates(flow { emit(found("a")) }, 20.seconds).toList()

        assertEquals(0L, currentTime)
        assertIs<PickerState.Finished>(states.last())
    }

    @Test
    fun aFailureIsReportedWithTheResultsSoFar() = runTest {
        val scan = flow {
            emit(found("a"))
            throw TransportException("Bluetooth is turned off. Turn it on and try again.")
        }

        val last = pickerStates(scan, 20.seconds).toList().last()

        val failed = assertIs<PickerState.Failed>(last)
        assertEquals("Bluetooth is turned off. Turn it on and try again.", failed.message)
        assertEquals(listOf("a"), failed.results.adapters.map { it.address })
    }

    @Test
    fun anErrorWithoutAMessageStillSaysWhatKind() = runTest {
        val last = pickerStates(flow { throw UnsupportedOperationException() }, 20.seconds).toList().last()

        assertEquals("UnsupportedOperationException", assertIs<PickerState.Failed>(last).message)
    }

    @Test
    fun theScanStopsWhenTheCollectorGoesAway() = runTest {
        var stopped = false
        val scan = flow<DiscoveredAdapter> {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }

        val job = launch { pickerStates(scan, 20.seconds).collect { } }
        runCurrent()
        assertFalse(stopped)
        job.cancel()
        runCurrent()

        assertTrue(stopped)
    }

    @Test
    fun theScanStopsWhenItsTimeIsUp() = runTest {
        var stopped = false
        val scan = flow<DiscoveredAdapter> {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }

        val job = launch { pickerStates(scan, 20.seconds).collect { } }
        advanceTimeBy(19.seconds)
        assertFalse(stopped)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertTrue(stopped)
        assertTrue(job.isCompleted)
    }
}
