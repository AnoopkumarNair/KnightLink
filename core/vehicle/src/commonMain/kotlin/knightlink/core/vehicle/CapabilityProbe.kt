package knightlink.core.vehicle

import knightlink.core.protocol.BusAddressing
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Finds out what an adapter and a vehicle can do, without changing anything on the car.
 *
 * Only adapter (AT) commands and OBD services 01 (live data) and 09 (vehicle information) are ever
 * sent. Services that clear, write or run routines, and sweeps of manufacturer services, are not part of
 * this probe by design (ADR 0005).
 *
 * @param timeSource measures round trips; inject a test time source to get exact numbers.
 */
public class CapabilityProbe(
    private val link: ProbeLink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
) {
    public data class Config(
        /** How many `01 00` round trips to time against the first ECU. Zero skips the benchmark. */
        val benchmarkSamples: Int = DEFAULT_BENCHMARK_SAMPLES,
        /** CAN addressing scheme (11-bit or 29-bit normal fixed). */
        val addressing: BusAddressing = BusAddressing.Can11Bit,
    ) {
        init {
            require(benchmarkSamples >= 0) { "benchmarkSamples must not be negative" }
        }
    }

    /** An ECU found by the sweep, with the first support bitmap it sent (reused so `01 00` is not asked twice). */
    private class Found(val requestId: Int, val responseId: Int, val firstBitmap: ByteArray?) {
        fun label(addressing: BusAddressing): String = addressing.formatId(requestId)
    }

    /** The first status word met that says the vehicle bus could not be reached; reset by every [run]. */
    private var busFault: ElmStatus? = null

    private class VehicleInfo(val vin: String?, val calibrationIds: List<String>, val name: String?)

    /**
     * Runs all stages in order and returns the report.
     *
     * @param onProgress called when each stage starts.
     * @throws ProbeException when the adapter does not answer, or no request address could be tried successfully.
     */
    public suspend fun run(onProgress: suspend (ProbeStage) -> Unit = {}): CapabilityReport {
        val started = timeSource.markNow()
        val warnings = ArrayList<String>()
        busFault = null

        onProgress(ProbeStage.ADAPTER)
        val adapter = readAdapter()

        onProgress(ProbeStage.ECU_SWEEP)
        val found = sweep(warnings)

        onProgress(ProbeStage.LIVE_DATA_SUPPORT)
        val supported = found.map { liveDataSupport(it, warnings) }

        onProgress(ProbeStage.VEHICLE_INFO)
        val info = found.map { vehicleInfo(it, warnings) }

        onProgress(ProbeStage.BENCHMARK)
        val roundTrips = found.firstOrNull()?.let { benchmark(it, warnings) }.orEmpty()

        onProgress(ProbeStage.DERIVED_FACTS)
        val ecus = found.mapIndexed { i, ecu ->
            EcuReport(
                requestId = ecu.label(config.addressing),
                responseId = config.addressing.formatId(ecu.responseId),
                name = info[i].name,
                calibrationIds = info[i].calibrationIds,
                supportedPids = supported[i].sorted().map { it.toPidHex() },
                vin = info[i].vin,
            )
        }
        // Bus status words only mean something when no ECU answered at all: one that does answer proves the bus is up.
        val unreachable = busFault.takeIf { ecus.isEmpty() }
        if (unreachable != null) warnings += "vehicle bus not reachable: the adapter said ${unreachable.name.replace('_', ' ')}"
        val median = roundTrips.takeIf { it.isNotEmpty() }?.let(::median)
        val medianMs = median?.toDouble(DurationUnit.MILLISECONDS)
        return CapabilityReport(
            adapter = adapter,
            timing = Timing(
                probeMs = started.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
                benchmarkSamples = roundTrips.size,
                medianRoundTripMs = medianMs,
                estimatedRequestsPerSecond = medianMs?.takeIf { it > 0.0 }?.let { MILLIS_PER_SECOND / it },
            ),
            ecus = ecus,
            derived = DerivedFacts.of(ecus),
            warnings = warnings,
            busFault = unreachable?.name,
        )
    }

    // Stage 1 ----------------------------------------------------------------------------------------------

    private suspend fun readAdapter(): AdapterInfo {
        val banner = atText("ATI")
        val description = atText("AT@1")
        val voltageText = atText("ATRV")
        val protocolNumber = atText("ATDPN")
        // "NaN" would parse but cannot be written to JSON.
        val voltage = voltageText?.trimEnd('V', 'v', ' ')?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return AdapterInfo(banner, description, voltageText, voltage, protocolNumber)
    }

    /** First reply line, or null when the adapter does not know the command (`?`). */
    private suspend fun atText(command: String): String? {
        val lines = try {
            link.command(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without an answer to its own commands the adapter is not usable, and the sweep would only time out eight times.
            throw ProbeException("The adapter did not answer $command: ${e.message}", e)
        }
        val trimmed = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty() || trimmed.any { it == "?" }) return null
        return trimmed.first()
    }

    // Stage 2 ----------------------------------------------------------------------------------------------

    private suspend fun sweep(warnings: MutableList<String>): List<Found> {
        if (config.addressing.extendedIds) return sweepExtended(warnings)
        val found = ArrayList<Found>()
        var failures = 0
        var lastFailure: Exception? = null
        for (requestId in SWEEP_REQUEST_IDS) {
            val response = try {
                link.request(requestId, payload(SERVICE_LIVE_DATA, 0x00), expectedResponses = 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One address timing out must not hide the ECUs on the others.
                failures++
                lastFailure = e
                warnings += "${config.addressing.formatId(requestId)}: request failed: ${e.message ?: e::class.simpleName}"
                continue
            }
            noteBusFault(response)
            val reply = response.positiveReply(SERVICE_LIVE_DATA, 0x00) ?: continue
            found += Found(requestId, reply.sourceId, reply.bitmap())
        }
        if (failures == SWEEP_REQUEST_IDS.count()) {
            throw ProbeException("Every request address failed; the adapter or the vehicle connection is not working", lastFailure)
        }
        return found
    }

    private suspend fun sweepExtended(warnings: MutableList<String>): List<Found> {
        val found = ArrayList<Found>()
        val seen = HashSet<Int>()
        try {
            val response = link.request(config.addressing.functionalRequestId, payload(SERVICE_LIVE_DATA, 0x00), expectedResponses = null)
            noteBusFault(response)
            for (msg in response.messages.filterIsInstance<DiagnosticResponse.Positive>()) {
                if (msg.service != SERVICE_LIVE_DATA || msg.data.firstOrNull()?.toInt()?.and(0xFF) != 0x00) continue
                val reqId = config.addressing.requestIdForResponse(msg.sourceId) ?: continue
                if (seen.add(reqId)) found += Found(reqId, msg.sourceId, msg.bitmap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "${config.addressing.formatId(config.addressing.functionalRequestId)}: functional sweep failed: ${e.message ?: e::class.simpleName}"
        }
        if (found.isNotEmpty()) return found
        // Fallback: probe common 29-bit OBD ECU addresses directly.
        var failures = 0
        var lastFailure: Exception? = null
        for (ecuByte in STANDARD_29BIT_ECUS) {
            val requestId = BusAddressing.Can29Bit.physicalRequestId(ecuByte)
            val response = try {
                link.request(requestId, payload(SERVICE_LIVE_DATA, 0x00), expectedResponses = 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastFailure = e
                warnings += "${config.addressing.formatId(requestId)}: request failed: ${e.message ?: e::class.simpleName}"
                continue
            }
            noteBusFault(response)
            val reply = response.positiveReply(SERVICE_LIVE_DATA, 0x00) ?: continue
            if (seen.add(requestId)) found += Found(requestId, reply.sourceId, reply.bitmap())
        }
        if (found.isEmpty() && failures == STANDARD_29BIT_ECUS.size) {
            throw ProbeException("Every request address failed; the adapter or the vehicle connection is not working", lastFailure)
        }
        return found
    }

    // Stage 3 ----------------------------------------------------------------------------------------------

    /** Follows the "next range" bit from `01 00` upwards. A range that does not answer ends the chain, keeping what was read. */
    private suspend fun liveDataSupport(ecu: Found, warnings: MutableList<String>): Set<Int> {
        val supported = LinkedHashSet<Int>()
        var base = 0
        var bitmap = ecu.firstBitmap
        if (bitmap == null) warnings += "${ecu.label(config.addressing)}: the 01 00 reply was too short to hold a support bitmap"
        while (bitmap != null) {
            val range = decodeSupportRange(base, bitmap)
            supported += range.pids
            if (!range.hasNext || base >= LAST_RANGE_BASE) break
            base += PIDS_PER_RANGE
            bitmap = ask(ecu, SERVICE_LIVE_DATA, base, warnings)?.bitmap()
            if (bitmap == null) warnings += "${ecu.label(config.addressing)}: no usable answer to 01 ${base.toPidHex()}; support map may be incomplete"
        }
        return supported
    }

    // Stage 4 ----------------------------------------------------------------------------------------------

    /** Reads only the items the ECU lists in its `09 00` bitmap; an ECU that does not answer it is not asked further. */
    private suspend fun vehicleInfo(ecu: Found, warnings: MutableList<String>): VehicleInfo {
        val types = ask(ecu, SERVICE_VEHICLE_INFO, 0x00, warnings)?.bitmap()?.let { decodeSupportRange(0, it).pids }.orEmpty()
        val vin = if (INFO_VIN in types) ask(ecu, SERVICE_VEHICLE_INFO, INFO_VIN, warnings)?.let { printableText(it.data, from = ITEM_TEXT_OFFSET) } else null
        val calibrationIds = if (INFO_CALIBRATION_ID in types) {
            ask(ecu, SERVICE_VEHICLE_INFO, INFO_CALIBRATION_ID, warnings)?.let(::calibrationIds).orEmpty()
        } else {
            emptyList()
        }
        val name = if (INFO_ECU_NAME in types) {
            ask(ecu, SERVICE_VEHICLE_INFO, INFO_ECU_NAME, warnings)?.let { printableText(it.data, from = ITEM_TEXT_OFFSET) }
        } else {
            null
        }
        return VehicleInfo(vin, calibrationIds, name)
    }

    /** Reply data is: PID, item count, then count items of [CALIBRATION_ID_LENGTH] bytes each, NUL-padded. */
    private fun calibrationIds(reply: DiagnosticResponse.Positive): List<String> {
        val count = reply.data.getOrNull(1)?.toInt()?.and(0xFF) ?: return emptyList()
        // Never read more items than announced, and never more than the data holds; a zero count with data is treated as "unknown".
        val starts = (ITEM_TEXT_OFFSET until reply.data.size step CALIBRATION_ID_LENGTH).toList()
        return (if (count > 0) starts.take(count) else starts)
            .mapNotNull { start -> printableText(reply.data, from = start, to = start + CALIBRATION_ID_LENGTH) }
    }

    // Stage 5 ----------------------------------------------------------------------------------------------

    /** Round trips of the answered requests. Unanswered ones are left out: a timeout says nothing about speed. */
    private suspend fun benchmark(ecu: Found, warnings: MutableList<String>): List<Duration> {
        val roundTrips = ArrayList<Duration>(config.benchmarkSamples)
        repeat(config.benchmarkSamples) {
            val mark = timeSource.markNow()
            val answered = ask(ecu, SERVICE_LIVE_DATA, 0x00, warnings)
            val elapsed = mark.elapsedNow()
            if (answered != null) roundTrips += elapsed
        }
        if (roundTrips.size < config.benchmarkSamples) {
            warnings += "${ecu.label(config.addressing)}: ${config.benchmarkSamples - roundTrips.size} of ${config.benchmarkSamples} benchmark requests were not answered"
        }
        return roundTrips
    }

    // Shared -----------------------------------------------------------------------------------------------

    /** Positive reply from [ecu] to `service pid`, or null when it stayed silent, refused or the request failed. */
    private suspend fun ask(ecu: Found, service: Int, pid: Int, warnings: MutableList<String>): DiagnosticResponse.Positive? {
        val response: ElmResponse = try {
            link.request(ecu.requestId, payload(service, pid), expectedResponses = countFor(pid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "${ecu.label(config.addressing)}: ${service.toPidHex()} ${pid.toPidHex()} failed: ${e.message ?: e::class.simpleName}"
            return null
        }
        noteBusFault(response)
        return response.positiveReply(service, pid, from = ecu.responseId)
    }

    private fun noteBusFault(response: ElmResponse) {
        if (busFault == null) busFault = response.busFault
    }

    /**
     * The adapter's response-count digit: 1 only for a bitmap, whose reply (service, PID and four bytes) fits one CAN frame. The VIN,
     * calibration ids and ECU name are multi-frame: with a count the adapter stops after the first frame and never sends flow control,
     * so the rest of the reply would never arrive.
     */
    private fun countFor(pid: Int): Int? = if (pid % PIDS_PER_RANGE == 0) 1 else null

    private fun payload(service: Int, pid: Int): ByteArray = byteArrayOf(service.toByte(), pid.toByte())

    private companion object {
        const val DEFAULT_BENCHMARK_SAMPLES = 10
        const val MILLIS_PER_SECOND = 1000.0

        /** Physical request ids of the first eight ECUs on an 11-bit OBD bus (replies come from id + 8). */
        val SWEEP_REQUEST_IDS = 0x7E0..0x7E7

        /** Common 29-bit ISO 15765-4 OBD ECU target addresses (ECM, secondary ECM, TCM, ABS/chassis, hybrid/battery). */
        val STANDARD_29BIT_ECUS = listOf(0x10, 0x11, 0x18, 0x1A, 0x1E)

        /** Start of the last range a service 01 bitmap can announce (`01 E0`). */
        const val LAST_RANGE_BASE = 0xE0

        const val INFO_VIN = 0x02
        const val INFO_CALIBRATION_ID = 0x04
        const val INFO_ECU_NAME = 0x0A

        /** Service 09 text items: data[0] is the PID, data[1] the item count, the text starts after them. */
        const val ITEM_TEXT_OFFSET = 2
        const val CALIBRATION_ID_LENGTH = 16
    }
}
