package knightlink.platform.ble

import knightlink.core.transport.ble.BleSerialProfile
import knightlink.core.transport.ble.BluetoothUuid
import kotlin.math.abs

/**
 * Decides whether an advertisement belongs to the adapter being looked for: it advertises the serial service, OR its
 * advertised name is one of the expected names. Name matching has to work on its own because many ELM-style adapters
 * do not put their service UUID in the advertisement, which is also why the scan itself uses no Android scan filter
 * (a filter on the service would hide exactly those adapters).
 */
internal class AdapterScanMatcher(
    profile: BleSerialProfile,
    names: List<String>,
) {
    private val serviceUuid = BluetoothUuid.normalize(profile.serviceUuid)
    private val expectedNames = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** [advertisedName] is compared ignoring case and surrounding spaces; service UUIDs by [BluetoothUuid.matches] rules. */
    fun matches(
        advertisedName: String?,
        advertisedServiceUuids: Collection<String>,
    ): Boolean =
        (advertisedName != null && advertisedName.trim().lowercase() in expectedNames) ||
            advertisedServiceUuids.any { BluetoothUuid.normalize(it) == serviceUuid }
}

/**
 * Collapses the stream of advertisements (an adapter advertises many times a second) into one result per address,
 * re-reporting only when something a user would see changed: the signal strength moved by at least [rssiStep] dBm, or
 * the name became known. A later advertisement without a name keeps the name already learned.
 *
 * Scan callbacks arrive one at a time, but the lock is cheap and makes the class safe whichever thread delivers them.
 */
internal class ScanDeduplicator(
    private val rssiStep: Int = DEFAULT_RSSI_STEP,
) {
    private val lastReported = HashMap<String, DiscoveredAdapter>()

    /** The result to report for this advertisement, or null if it adds nothing to what was reported before. */
    @Synchronized
    fun accept(
        address: String,
        name: String?,
        rssi: Int,
    ): DiscoveredAdapter? {
        val previous = lastReported[address]
        val knownName = name ?: previous?.name
        if (previous != null && knownName == previous.name && abs(rssi - previous.rssi) < rssiStep) return null
        return DiscoveredAdapter(address, knownName, rssi).also { lastReported[address] = it }
    }

    private companion object {
        const val DEFAULT_RSSI_STEP = 5
    }
}

/** Readable text for the error codes of `ScanCallback.onScanFailed`. */
internal object ScanFailure {
    private const val ALREADY_STARTED = 1
    private const val REGISTRATION_FAILED = 2
    private const val INTERNAL_ERROR = 3
    private const val FEATURE_UNSUPPORTED = 4
    private const val OUT_OF_HARDWARE_RESOURCES = 5
    private const val SCANNING_TOO_FREQUENTLY = 6

    fun describe(errorCode: Int): String =
        when (errorCode) {
            ALREADY_STARTED -> "A Bluetooth scan is already running (scan error 1)"
            REGISTRATION_FAILED -> "Android could not start the Bluetooth scan; turn Bluetooth off and on and try again (scan error 2)"
            INTERNAL_ERROR -> "The Bluetooth stack failed to start a scan; turn Bluetooth off and on and try again (scan error 3)"
            FEATURE_UNSUPPORTED -> "This phone does not support the kind of Bluetooth scan needed (scan error 4)"
            OUT_OF_HARDWARE_RESOURCES -> "The phone's Bluetooth hardware has no capacity left for a scan; close other Bluetooth apps (scan error 5)"
            SCANNING_TOO_FREQUENTLY -> "Bluetooth scans were started too often; wait half a minute and try again (scan error 6)"
            else -> "The Bluetooth scan failed (scan error $errorCode)"
        }
}
