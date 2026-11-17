package knightlink.core.signals

import knightlink.core.common.hexToBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared SAE J1979 service 01 layer and the Harrier layer above it: structure, and every PID decoded from bytes whose result was
 * worked out by hand from the standard's formula (or, where noted, read from the real-car recording).
 */
class StandardDefinitionsTest {
    private val definitions = File(File(System.getProperty("knightlink.repoRoot")), "definitions")

    private fun load(id: String): DefinitionFile = DefinitionJson.parse(File(definitions, "$id.json").readText())

    private val standard = load("standard/sae-j1979-mode01")
    private val harrier = load("vehicles/tata/harrier-2020-kryotec-at")
    private val catalog = SignalCatalog.of(listOf(standard, harrier))

    private fun decode(pid: String, hex: String, ecu: String? = null): Map<String, Double> =
        assertNotNull(catalog.find(1, pid, ecu), "no definition for PID $pid").decode(hex.hexToBytes()).associate { it.id to it.value }

    private fun check(pid: String, hex: String, expected: Map<String, Double>) {
        val actual = decode(pid, hex)
        for ((id, value) in expected) {
            assertEquals(value, assertNotNull(actual[id], "PID $pid: $id was not decoded (got ${actual.keys})"), 0.0005, "PID $pid: $id")
        }
    }

    // --- structure -----------------------------------------------------------------------------------------

    @Test
    fun everyStandardPidStatesItsLengthAndATierAndNoFormulaReadsPastTheLength() {
        for (parameter in standard.parameters) {
            val name = "PID ${parameter.pid}"
            val length = assertNotNull(parameter.length, "$name has no length")
            assertNotNull(parameter.poll, "$name has no poll tier")
            // Byte layouts written from memory of the standard and not yet checked against it or a car are `inferred`, so
            // nothing safety-related (alerts, spoken advice) relies on them until someone verifies them.
            val expected = if (parameter.pid in UNVERIFIED_LAYOUTS) Provenance.INFERRED else Provenance.STANDARD
            assertEquals(expected, parameter.provenance, name)
            for (signal in parameter.signals) {
                val needs = Expression.compile(signal.formula).requiredBytes
                assertTrue(needs <= length, "$name ${signal.id}: the formula reads $needs bytes of a $length byte reply")
            }
        }
    }

    @Test
    fun theStandardCoversEveryServiceOnePidTheHarrierReports() {
        val reported = (
            "01 04 05 0B 0C 0D 10 13 1C 1F 21 2F 30 31 33 34 42 45 46 49 4A 4C 4F 62 63 68 69 6B 6D 71 78 7A 83 85 88 8B 8E 92 98 9D A1 " +
                "01 30 31 01 04 05 0C 0D 1C 21 33 46 85 9B"
            ).split(" ").toSet()
        for (pid in reported) {
            assertNotNull(catalog.find(1, pid, "ecm") ?: catalog.find(1, pid, "rdcm"), "PID $pid has no definition")
        }
    }

    @Test
    fun eachPidIsDefinedOnceAcrossTheLayers() {
        // The vehicle layer keeps only what the standard does not say for this car; repeating a standard PID would just shadow it.
        val inStandard = standard.parameters.map { it.pid.uppercase() }
        assertEquals(inStandard.size, inStandard.toSet().size, "a PID is defined twice in the standard layer")
        val shadowed = harrier.parameters.filter { it.pid.uppercase() in inStandard }
        assertTrue(shadowed.isEmpty(), "the vehicle layer repeats standard PIDs: ${shadowed.map { it.pid }}")
        assertTrue(harrier.parameters.all { it.provenance != Provenance.STANDARD })
    }

    @Test
    fun signalIdsAreUniqueAcrossTheStandardLayer() {
        val ids = standard.parameters.flatMap { p -> p.signals.map { it.id } }
        assertEquals(emptyList(), ids.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }

    @Test
    fun theTiersPutDrivingFeelFirstAndStaticItemsOnce() {
        fun tier(pid: String) = assertNotNull(catalog.find(1, pid)).tier
        for (pid in listOf("04", "0B", "0C", "0D", "11", "49", "62")) assertEquals(PollTier.FAST, tier(pid), pid)
        for (pid in listOf("10", "78", "7A", "6D", "9D", "8B")) assertEquals(PollTier.NORMAL, tier(pid), pid)
        for (pid in listOf("05", "2F", "42", "46", "31", "68")) assertEquals(PollTier.SLOW, tier(pid), pid)
        for (pid in listOf("1C", "13", "63")) assertEquals(PollTier.ONCE, tier(pid), pid)
        assertTrue(PollTier.FAST.period.inWholeMilliseconds in 100..200)
        assertEquals(1000, PollTier.NORMAL.period.inWholeMilliseconds)
        assertTrue(PollTier.SLOW.period.inWholeMilliseconds in 5000..10000)
        assertTrue(PollTier.ONCE.period.isInfinite())
    }

    @Test
    fun aParameterWithoutATierIsNormalAndOneWithoutALengthHasNone() {
        val file = DefinitionJson.parse(
            """{"schemaVersion":1,"id":"x","kind":"standard","title":"X","parameters":[
               {"service":"01","pid":"0C","provenance":"standard","signals":[{"id":"a","formula":"A","unit":"u"}]},
               {"service":"01","pid":"0D","provenance":"standard","length":1,"poll":"fast","signals":[{"id":"b","formula":"A","unit":"u"}]}]}""",
        )
        val parsed = SignalCatalog.of(listOf(file))
        assertEquals(PollTier.NORMAL, parsed.find(1, "0C")!!.tier)
        assertNull(parsed.find(1, "0C")!!.length)
        assertEquals(PollTier.FAST, parsed.find(1, "0D")!!.tier)
        assertEquals(1, parsed.find(1, "0D")!!.length)
    }

    @Test
    fun anUnknownTierOrAnImpossibleLengthIsRejected() {
        val template = """{"schemaVersion":1,"id":"x","kind":"standard","title":"X","parameters":[
            {"service":"01","pid":"0C","provenance":"standard",%s"signals":[{"id":"a","formula":"A","unit":"u"}]}]}"""
        assertFailsWith<DefinitionException> { DefinitionJson.parse(template.format(""""poll":"often",""")) }
        assertFailsWith<DefinitionException> { SignalCatalog.of(listOf(DefinitionJson.parse(template.format(""""length":0,""")))) }
        assertFailsWith<DefinitionException> { SignalCatalog.of(listOf(DefinitionJson.parse(template.format(""""length":27,""")))) }
    }

    // --- decoding, by hand-computed examples -------------------------------------------------------------

    @Test
    fun monitorStatusAndFuelSystem() {
        check("01", "82 07 E5 00", mapOf("diag.mil_on" to 1.0, "diag.dtc_count" to 2.0))
        check("03", "02 04", mapOf("fuel.system1_status" to 2.0, "fuel.system2_status" to 4.0))
    }

    @Test
    fun trimsPressuresAndTiming() {
        check("06", "80", mapOf("fuel.trim_short_b1_pct" to 0.0))
        check("07", "00", mapOf("fuel.trim_long_b1_pct" to -100.0))
        check("09", "FF", mapOf("fuel.trim_long_b2_pct" to 99.2188))
        check("0A", "64", mapOf("fuel.pressure_gauge_kpa" to 300.0))
        check("0E", "90", mapOf("engine.timing_advance_deg" to 8.0))
        check("22", "10 00", mapOf("fuel.rail_pressure_vacuum_kpa" to 323.584))
        check("23", "01 F4", mapOf("fuel.rail_pressure_kpa" to 5000.0))
        check("59", "0B B8", mapOf("fuel.rail_pressure_abs_kpa" to 30000.0))
    }

    @Test
    fun oxygenSensors() {
        check("14", "64 80", mapOf("o2.b1s1_voltage" to 0.5, "o2.b1s1_trim_pct" to 0.0))
        check("1B", "C8 00", mapOf("o2.b2s4_voltage" to 1.0, "o2.b2s4_trim_pct" to -100.0))
        check("24", "80 00 40 00", mapOf("o2.b1s1_wr_lambda" to 1.0, "o2.b1s1_wr_voltage" to 2.0))
        check("34", "80 00 81 00", mapOf("o2.b1s1_wrc_lambda" to 1.0, "o2.b1s1_wrc_current_ma" to 1.0))
        check("37", "40 00 7F 00", mapOf("o2.b1s4_wrc_lambda" to 0.5, "o2.b1s4_wrc_current_ma" to -1.0))
        check("13", "33", mapOf("o2.sensors_present_2bank" to 51.0))
        check("3C", "0F A0", mapOf("catalyst.b1s1_temp" to 360.0))
        check("3F", "00 00", mapOf("catalyst.b2s2_temp" to -40.0))
    }

    @Test
    fun loadPedalAndThrottle() {
        check("43", "00 FF", mapOf("engine.load_absolute_pct" to 100.0))
        check("44", "80 00", mapOf("engine.lambda_commanded" to 1.0))
        check("45", "FF", mapOf("engine.throttle_rel_pct" to 100.0))
        check("49", "80", mapOf("engine.pedal_d_pct" to 50.196))
        check("4A", "00", mapOf("engine.pedal_e_pct" to 0.0))
        check("4C", "FF", mapOf("engine.throttle_commanded_pct" to 100.0))
        check("5A", "40", mapOf("engine.pedal_rel_pct" to 25.098))
    }

    @Test
    fun limitsCountersAndFuel() {
        check(
            "4F",
            "01 02 03 04",
            mapOf(
                "limits.max_lambda" to 1.0,
                "limits.max_o2_voltage" to 2.0,
                "limits.max_o2_current_ma" to 3.0,
                "limits.max_map_kpa" to 40.0,
            ),
        )
        check("50", "20 00 00 00", mapOf("limits.max_maf_gps" to 320.0))
        check("1F", "01 2C", mapOf("engine.run_time_s" to 300.0))
        check("4D", "00 0A", mapOf("time.with_mil_min" to 10.0))
        check("5D", "6B 00", mapOf("fuel.injection_timing_deg" to 4.0))
        check("5E", "00 64", mapOf("fuel.rate_lph" to 5.0))
        check("2C", "FF", mapOf("egr.commanded_pct" to 100.0))
        check("2D", "90", mapOf("egr.error_pct" to 12.5))
    }

    @Test
    fun torque() {
        check("61", "96", mapOf("engine.torque_demand_pct" to 25.0))
        check("62", "7D", mapOf("engine.torque_actual_pct" to 0.0))
        check("63", "01 90", mapOf("engine.torque_reference_nm" to 400.0))
        check(
            "64",
            "7D 96 AA FF 00",
            mapOf(
                "engine.torque_curve_idle_pct" to 0.0,
                "engine.torque_curve_p1_pct" to 25.0,
                "engine.torque_curve_p2_pct" to 45.0,
                "engine.torque_curve_p3_pct" to 130.0,
                "engine.torque_curve_p4_pct" to -125.0,
            ),
        )
        check("8E", "8C", mapOf("engine.torque_friction_pct" to 15.0))
    }

    @Test
    fun multiSensorTemperaturesAndFlow() {
        check("66", "03 01 00 02 00", mapOf("intake.maf_supported_mask" to 3.0, "intake.maf_a" to 8.0, "intake.maf_b" to 16.0))
        check("67", "03 50 60", mapOf("engine.coolant_temp_s1" to 40.0, "engine.coolant_temp_s2" to 56.0))
        check(
            "68",
            "3F 3C 3D 3E 4C 4D 4E",
            mapOf(
                "intake.air_temp_b1s1" to 20.0,
                "intake.air_temp_b1s3" to 22.0,
                "intake.air_temp_b2s1" to 36.0,
                "intake.air_temp_b2s3" to 38.0,
            ),
        )
        check("84", "64", mapOf("intake.manifold_surface_temp" to 60.0))
        check(
            "6B",
            "B0 5A 6E 50 64",
            mapOf(
                "egr.temp_supported_mask" to 11.0,
                "egr.temp_b1_inlet" to 50.0,
                "egr.temp_b1_outlet" to 70.0,
                "egr.temp_b2_inlet" to 40.0,
                "egr.temp_b2_outlet" to 60.0,
            ),
        )
    }

    @Test
    fun egrIntakeFlowAndTurbo() {
        check(
            "69",
            "3F 80 40 90 FF 00 40",
            mapOf(
                "egr.a_commanded_pct" to 50.196,
                "egr.a_actual_pct" to 25.098,
                "egr.a_error_pct" to 12.5,
                "egr.b_commanded_pct" to 100.0,
                "egr.b_actual_pct" to 0.0,
                "egr.b_error_pct" to -50.0,
            ),
        )
        check(
            "6A",
            "0F 80 40 FF 00",
            mapOf(
                "intake.air_flow_a_commanded_pct" to 50.196,
                "intake.air_flow_a_position_pct" to 25.098,
                "intake.air_flow_b_commanded_pct" to 100.0,
                "intake.air_flow_b_position_pct" to 0.0,
            ),
        )
        check(
            "71",
            "3F 80 40 00 FF 05",
            mapOf(
                "turbo.vgt_a_commanded_pct" to 50.196,
                "turbo.vgt_a_actual_pct" to 25.098,
                "turbo.vgt_b_commanded_pct" to 0.0,
                "turbo.vgt_b_actual_pct" to 100.0,
                "turbo.vgt_a_status" to 1.0,
                "turbo.vgt_b_status" to 1.0,
            ),
        )
    }

    @Test
    fun fuelPressureControl() {
        check(
            "6D",
            "FC 03 20 03 18 64 00 00 00 00 00",
            mapOf(
                "fuel.rail_a_commanded_kpa" to 8000.0,
                "fuel.rail_a_actual_kpa" to 7920.0,
                "fuel.rail_a_temp" to 60.0,
                "fuel.rail_b_commanded_kpa" to 0.0,
                "fuel.rail_b_temp" to -40.0,
            ),
        )
        check(
            "6D",
            "FC 00 00 00 00 28 0C 80 0C 00 50",
            mapOf(
                "fuel.rail_b_commanded_kpa" to 32000.0,
                "fuel.rail_b_actual_kpa" to 30720.0,
                "fuel.rail_b_temp" to 40.0,
            ),
        )
        check(
            "92",
            "00 05",
            mapOf(
                "fuel.cl_pressure_a" to 1.0,
                "fuel.cl_quantity_a" to 0.0,
                "fuel.cl_timing_a" to 1.0,
                "fuel.cl_balance_a" to 0.0,
                "fuel.cl_balance_b" to 0.0,
            ),
        )
    }

    @Test
    fun exhaustTemperatures() {
        check(
            "78",
            "0F 06 F2 05 89 03 97 02 C9",
            mapOf(
                "exhaust.egt_supported_mask" to 15.0,
                "exhaust.egt_b1s1" to 137.8,
                "exhaust.egt_b1s2" to 101.7,
                "exhaust.egt_b1s4" to 31.3,
            ),
        )
        check("79", "03 0F A0 00 00", mapOf("exhaust.egt_b2_supported_mask" to 3.0, "exhaust.egt_b2s1" to 360.0, "exhaust.egt_b2s2" to -40.0))
        check("98", "0F 0F A0 07 D0 00 00 00 00", mapOf("exhaust.egt_b1s5" to 360.0, "exhaust.egt_b1s6" to 160.0))
        check(
            "7C",
            "0F 0F A0 07 D0 03 E8 00 64",
            mapOf(
                "dpf.inlet_temp" to 360.0,
                "dpf.outlet_temp" to 160.0,
                "dpf.b2_inlet_temp" to 60.0,
                "dpf.b2_outlet_temp" to -30.0,
            ),
        )
    }

    @Test
    fun theRealFirstFrameOfTheEgtReplyDecodesTheSensorItHolds() {
        // The recording holds only the first CAN frame of this reply (the adapter cut it): A = 0F and sensor 1 = 02 A7. The rest is left out, not zero.
        val partial = assertNotNull(catalog.find(1, "78")).decode("0F 02 A7 02".hexToBytes()).associate { it.id to it.value }
        assertEquals(27.9, partial.getValue("exhaust.egt_b1s1"), 0.0005)
        assertNull(partial["exhaust.egt_b1s2"])
    }

    @Test
    fun dpfPressuresIncludeNegativeDifferentialPressure() {
        check("7A", "07 00 64 27 10 27 74", mapOf("dpf.delta_pressure_kpa" to 1.0, "dpf.inlet_pressure_kpa" to 100.0, "dpf.outlet_pressure_kpa" to 101.0))
        check("7A", "07 FF 9C 27 10 27 74", mapOf("dpf.delta_pressure_kpa" to -1.0))
        check("7A", "07 80 00 00 00 00 00", mapOf("dpf.delta_pressure_kpa" to -327.68))
        check("7B", "07 7F FF 00 00 00 00", mapOf("dpf.b2_delta_pressure_kpa" to 327.67))
        check(
            "8B",
            "0F 02 FF 00 EF 00 B3",
            mapOf(
                "dpf.regen_status" to 2.0,
                "dpf.soot_pct" to 100.0,
                "dpf.avg_regen_interval_min" to 239.0,
                "dpf.avg_regen_distance_km" to 179.0,
            ),
        )
    }

    @Test
    fun noxAndReagent() {
        check(
            "83",
            "F0 00 64 00 C8 00 00 04 00",
            mapOf(
                "exhaust.nox_supported_mask" to 15.0,
                "exhaust.nox_b1s1_ppm" to 100.0,
                "exhaust.nox_b1s2_ppm" to 200.0,
                "exhaust.nox_b2s1_ppm" to 0.0,
                "exhaust.nox_b2s2_ppm" to 1024.0,
            ),
        )
        check("A1", "F0 03 E8 00 00 00 00 00 00", mapOf("exhaust.nox_corrected_b1s1_ppm" to 1000.0, "exhaust.nox_corrected_b1s2_ppm" to 0.0))
        check(
            "85",
            "F0 00 64 00 C8 80 00 00 01 00",
            mapOf("def.consumption_rate_lph" to 0.5, "def.demanded_rate_lph" to 1.0, "def.reagent_level_pct" to 50.196, "def.warning_time_s" to 256.0),
        )
        check("9D", "00 2B 00 2B", mapOf("fuel.rate_gps" to 0.86, "fuel.vehicle_rate_gps" to 0.86))
    }

    @Test
    fun scrInducement() {
        val v = decode("88", "8A 21 00 00 64 00 C8 01 2C 01 90 01 F4")
        assertEquals(1.0, v.getValue("def.inducement_active"))
        assertEquals(1.0, v.getValue("def.inducement_reason_nox"))
        assertEquals(0.0, v.getValue("def.inducement_reason_consumption"))
        assertEquals(1.0, v.getValue("def.inducement_reason_reagent"))
        assertEquals(0.0, v.getValue("def.inducement_reason_level_low"))
        assertEquals(1.0, v.getValue("def.inducement_l2_reagent"))
        assertEquals(1.0, v.getValue("def.inducement_l1_level_low"))
        assertEquals(0.0, v.getValue("def.inducement_l2_nox"))
        assertEquals(0.0, v.getValue("def.inducement_l4_nox"))
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0, 500.0), listOf("1n", "1d", "2n", "3n", "4n").map { v.getValue("def.inducement_dist_${it}_km") })
    }

    // --- the real car -----------------------------------------------------------------------------------------

    @Test
    fun realHarrierBytesFromTheRecording() {
        // Values taken from the scrubbed recording (fixtures/raw), raw bytes as the engine ECU sent them mid-drive.
        check("0C", "0F 64", mapOf("engine.rpm" to 985.0))
        check("0D", "0B", mapOf("vehicle.speed" to 11.0))
        check("05", "47", mapOf("engine.coolant_temp" to 31.0))
        check("0B", "3F", mapOf("intake.map_kpa" to 63.0))
        check("10", "05 7A", mapOf("intake.maf" to 14.02))
        check("2F", "9B", mapOf("fuel.level_pct" to 60.784))
        check("42", "37 DC", mapOf("electrical.module_voltage" to 14.300))
        check("46", "42", mapOf("ambient.air_temp" to 26.0))
        check("9D", "00 2B 00 2B", mapOf("fuel.rate_gps" to 0.86))
        assertEquals(23.0, decode("9B", "0E 81 3F F3", ecu = "rdcm").getValue("def.temp"), 0.0005)
        assertEquals(95.294, decode("9B", "0E 81 3F F3", ecu = "rdcm").getValue("def.level_pct"), 0.001)
    }

    private companion object {
        /** PIDs whose byte layout is not yet confirmed against SAE J1979-DA or a vehicle (see the polling work's report). */
        val UNVERIFIED_LAYOUTS = setOf("22", "23", "4F", "50", "5D", "66", "67", "69", "6A", "6B", "71", "7A", "7B", "83", "85", "88", "92", "98", "99", "A1")
    }
}
