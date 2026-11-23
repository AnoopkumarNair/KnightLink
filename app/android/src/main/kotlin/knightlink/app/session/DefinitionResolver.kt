package knightlink.app.session

import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import knightlink.core.runtime.ResolvedVehicle
import knightlink.core.runtime.VehicleResolver
import knightlink.core.scan.DidSweepPlan
import knightlink.core.signals.SignalCatalog
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.ModelYear

/**
 * Chooses the vehicle definition from the VIN the probe found. A definition applies only when the VIN starts with the manufacturer code
 * the definition names, and the definition's candidate file (when it has one) says it applies to that VIN. The model year the VIN encodes is only logged
 * when it is outside the definition's years. Any other car, and any car without a VIN, gets the standard signals only and is
 * [ResolvedVehicle.known] false: none of the identifier sweeps, the body-unit check, `10 01`, passive capture or the features of one car.
 *
 * @param latestYear the latest model year a car may have (the year after today's)
 */
class DefinitionResolver(
    private val store: DefinitionStore,
    private val generic: SignalCatalog,
    private val sweepPlan: DidSweepPlan?,
    private val latestYear: Int,
    private val log: (String) -> Unit = {},
) : VehicleResolver {
    override fun resolve(report: CapabilityReport): ResolvedVehicle {
        val vin = report.ecus.firstNotNullOfOrNull { it.vin }
        if (vin != null) {
            for (id in store.vehicleIds()) {
                val match = try {
                    match(id, vin, report)
                } catch (e: DefinitionStoreException) {
                    log("definition $id left out: ${e.message}")
                    null
                }
                if (match != null) return match
            }
        }
        return ResolvedVehicle(generic, known = false)
    }

    private fun match(id: String, vin: String, report: CapabilityReport): ResolvedVehicle? {
        val info = store.vehicleInfo(id) ?: return null
        val wmi = info.wmi ?: return null
        if (!vin.startsWith(wmi, ignoreCase = true)) return null
        // The manufacturer code only pre-selects: another model of the make (a car with other units) is an unknown car.
        for (unit in info.probeFingerprint) {
            val found = report.ecus.firstOrNull { it.requestId.equals(unit.requestId, ignoreCase = true) }
            if (found == null || found.supportedPids.size != unit.pids) {
                log(
                    "definition $id left out: the probe does not look like it (${unit.requestId}: ${found?.supportedPids?.size ?: "no answer"} PIDs, the definition says ${unit.pids})",
                )
                return null
            }
        }
        val candidates = store.candidateChecks(id)
        if (candidates != null && !candidates.appliesTo(vin)) return null
        // A remembered probe has its VIN masked after the manufacturer code: the year is then unknown, and the manufacturer code decides.
        val year = ModelYear.fromVin(vin, latestYear)
        // The year only informs: the tenth character of an Indian VIN is not always the model year, and the other checks decide.
        if (year != null && (year < info.yearFrom || (info.yearTo != null && year > info.yearTo!!))) {
            log("definition $id kept although the VIN's year character decodes to $year (the definition names ${info.yearFrom}-${info.yearTo ?: "on"})")
        }
        return ResolvedVehicle(
            catalog = store.vehicleCatalog(id),
            known = true,
            definitionId = id,
            model = info.model,
            candidateChecks = candidates,
            seedScan = store.discoverySeed(id),
            scanPlan = sweepPlan,
        )
    }
}
