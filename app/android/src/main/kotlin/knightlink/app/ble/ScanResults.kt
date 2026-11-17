package knightlink.app.ble

import knightlink.platform.ble.DiscoveredAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The adapters seen so far by one scan, ready to list: one entry per address, strongest signal first.
 * Immutable; [with] returns the updated list.
 */
data class ScanResults(val adapters: List<DiscoveredAdapter> = emptyList()) {
    /**
     * Merges one scan result. A result for a known address replaces the old one (new signal strength), but a result that
     * carries no name keeps the name learned earlier. Equal signal strengths are ordered by name, then address, so the list
     * does not shuffle between refreshes.
     */
    fun with(found: DiscoveredAdapter): ScanResults {
        val previous = adapters.firstOrNull { it.address == found.address }
        val merged = found.copy(name = found.name?.takeIf { it.isNotBlank() } ?: previous?.name)
        val updated = adapters.filterNot { it.address == found.address } + merged
        return ScanResults(updated.sortedWith(compareByDescending<DiscoveredAdapter> { it.rssi }.thenBy { it.name.orEmpty() }.thenBy { it.address }))
    }
}

/** What the adapter picker shows. */
sealed interface PickerState {
    val results: ScanResults

    /** The scan is running. */
    data class Scanning(override val results: ScanResults) : PickerState

    /** The scan ran for its full time; [results] may be empty. */
    data class Finished(override val results: ScanResults) : PickerState

    /** The scan could not start or stopped; [message] is fit to show. */
    data class Failed(val message: String, override val results: ScanResults) : PickerState
}

val DEFAULT_SCAN_TIME: Duration = 20.seconds

/**
 * The states of one scan: [PickerState.Scanning] with every update while [scan] runs, then [PickerState.Finished] after
 * [scanTime], or [PickerState.Failed] if [scan] fails. The scan is stopped (collection cancelled) in both cases, and when the
 * collector of this flow goes away.
 */
fun pickerStates(scan: Flow<DiscoveredAdapter>, scanTime: Duration = DEFAULT_SCAN_TIME): Flow<PickerState> = flow {
    var results = ScanResults()
    emit(PickerState.Scanning(results))
    val failure: Exception? = try {
        withTimeoutOrNull(scanTime) {
            scan.collect {
                results = results.with(it)
                emit(PickerState.Scanning(results))
            }
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A refused scan (permission, Bluetooth off: a TransportException), a broken definition file or an unexpected
        // platform error: shown in the picker, never a crash.
        e
    }
    emit(if (failure == null) PickerState.Finished(results) else PickerState.Failed(failure.message ?: failure::class.simpleName.orEmpty(), results))
}
