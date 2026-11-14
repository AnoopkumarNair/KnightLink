package knightlink.core.vehicle

import kotlin.test.Test
import kotlin.test.assertEquals

fun ecu(requestId: Int, vararg pids: String, vin: String? = null, name: String? = null, calibrationIds: List<String> = emptyList()) = EcuReport(
    requestId = requestId.toCanIdHex(),
    responseId = (requestId + 8).toCanIdHex(),
    name = name,
    calibrationIds = calibrationIds,
    supportedPids = pids.toList(),
    vin = vin,
)

class DerivedFactsTest {
    @Test
    fun directFuelRateWinsOverEverythingElse() {
        assertEquals(FuelMethod.DIRECT_RATE, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B", "0C", "10", "9D"))))
        assertEquals(FuelMethod.DIRECT_RATE, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B", "0C", "10", "5E"))))
    }

    @Test
    fun fuelRateOnAnyEcuCounts() {
        val ecus = listOf(ecu(0x7E0, "05"), ecu(0x7E4, "5E"))
        assertEquals(FuelMethod.DIRECT_RATE, DerivedFacts.fuelMethod(ecus))
    }

    @Test
    fun massAirFlowIsUsedWhenThereIsNoDirectRate() {
        assertEquals(FuelMethod.MAF, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B", "0C", "10"))))
    }

    @Test
    fun speedDensityNeedsBothManifoldPressureAndEngineSpeed() {
        assertEquals(FuelMethod.SPEED_DENSITY, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B", "0C"))))
        assertEquals(FuelMethod.UNKNOWN, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B"))))
        assertEquals(FuelMethod.UNKNOWN, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0C"))))
    }

    @Test
    fun speedDensityPoolsSupportAcrossEcus() {
        assertEquals(FuelMethod.SPEED_DENSITY, DerivedFacts.fuelMethod(listOf(ecu(0x7E0, "0B"), ecu(0x7E1, "0C"))))
    }

    @Test
    fun noEcusMeansUnknownFuelMethod() {
        assertEquals(FuelMethod.UNKNOWN, DerivedFacts.fuelMethod(emptyList()))
    }

    @Test
    fun anyOfTheDpfPidsMeansDpfData() {
        for (pid in listOf("7A", "7B", "7C", "8B")) {
            assertEquals(true, DerivedFacts.hasDpfData(listOf(ecu(0x7E0, "05"), ecu(0x7E4, pid))), pid)
        }
        assertEquals(false, DerivedFacts.hasDpfData(listOf(ecu(0x7E0, "05", "7D", "8A", "8C"))))
        assertEquals(false, DerivedFacts.hasDpfData(emptyList()))
    }

    @Test
    fun duplicatesListEveryPidAnsweredByMoreThanOneEcuWithTheLowestRequestIdAsPrimary() {
        // Deliberately out of order: the primary is the lowest request id, not the first in the list.
        val ecus = listOf(ecu(0x7E4, "05", "0C", "9B"), ecu(0x7E0, "05", "0C", "0D"), ecu(0x7E1, "0D", "31"))
        assertEquals(
            listOf(
                DuplicatePid("05", listOf("7E0", "7E4"), primary = "7E0"),
                DuplicatePid("0C", listOf("7E0", "7E4"), primary = "7E0"),
                DuplicatePid("0D", listOf("7E0", "7E1"), primary = "7E0"),
            ),
            DerivedFacts.duplicates(ecus),
        )
    }

    @Test
    fun threeWayDuplicateListsAllOwners() {
        val ecus = listOf(ecu(0x7E1, "0D"), ecu(0x7E0, "0D"), ecu(0x7E2, "0D"))
        assertEquals(listOf(DuplicatePid("0D", listOf("7E0", "7E1", "7E2"), "7E0")), DerivedFacts.duplicates(ecus))
    }

    @Test
    fun noDuplicatesWhenEveryPidHasOneOwner() {
        assertEquals(emptyList(), DerivedFacts.duplicates(listOf(ecu(0x7E0, "05"), ecu(0x7E1, "0C"))))
        assertEquals(emptyList(), DerivedFacts.duplicates(emptyList()))
    }

    @Test
    fun ofCombinesAllThree() {
        val facts = DerivedFacts.of(listOf(ecu(0x7E0, "05", "8B", "9D"), ecu(0x7E4, "05")))
        assertEquals(DerivedFacts(FuelMethod.DIRECT_RATE, true, listOf(DuplicatePid("05", listOf("7E0", "7E4"), "7E0"))), facts)
    }
}
