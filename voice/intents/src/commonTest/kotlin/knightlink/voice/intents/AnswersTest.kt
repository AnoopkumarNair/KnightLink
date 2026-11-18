package knightlink.voice.intents

import knightlink.core.signals.Provenance
import knightlink.core.telemetry.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AnswersTest {
    private val dataIntents = Intent.entries - listOf(Intent.REPEAT, Intent.STOP, Intent.UNKNOWN)

    private fun Answer.quantity(name: String): Double = (args.getValue(name) as AnswerArg.Quantity).value

    private fun Answer.phraseKey(name: String): String = (args.getValue(name) as AnswerArg.Phrase).answer.key

    // ---- link state -------------------------------------------------------------------------------------------------------

    @Test
    fun everyDataIntentSaysTheLinkStateWhenNotLive() {
        val readings = arrayOf(reading("engine.coolant_temp", 90.0), reading("dpf.regen_status", 0.0), reading("fuel.level_pct", 50.0))
        val expected = mapOf(
            LinkStatus.NOT_CONNECTED to AnswerKeys.LINK_NOT_CONNECTED,
            LinkStatus.CONNECTING to AnswerKeys.LINK_CONNECTING,
            LinkStatus.WAITING_FOR_IGNITION to AnswerKeys.LINK_WAITING_FOR_IGNITION,
            LinkStatus.RECONNECTING to AnswerKeys.LINK_RECONNECTING,
        )
        for ((link, key) in expected) {
            for (intent in dataIntents) {
                assertEquals(key, answerOf(intent, context(*readings, link = link)).key, "$intent while $link")
            }
            assertEquals(AnswerKeys.HELP, answerOf(Intent.UNKNOWN, context(link = link)).key)
        }
        assertEquals("I'm not connected to the car.", textOf(Answer(AnswerKeys.LINK_NOT_CONNECTED)))
        assertEquals("The adapter is connected but the car isn't answering. Is the ignition on?", textOf(Answer(AnswerKeys.LINK_WAITING_FOR_IGNITION)))
    }

    @Test
    fun repeatAndStopAreNotAnsweredFromTelemetry() {
        assertFailsWith<IllegalArgumentException> { answerOf(Intent.REPEAT, context()) }
        assertFailsWith<IllegalArgumentException> { answerOf(Intent.STOP, context()) }
    }

    @Test
    fun everyDataIntentSaysUnknownWithNothingKnown() {
        for (intent in dataIntents - listOf(Intent.WARNINGS, Intent.STATUS, Intent.SWITCH_OFF, Intent.DPF)) {
            assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(intent, context()).key, "$intent")
        }
        assertEquals("I don't have a current coolant temperature yet.", textOf(answerOf(Intent.COOLANT_TEMPERATURE, context())))
        assertEquals("I don't have a current DPF reading yet.", textOf(answerOf(Intent.DPF, context())))
    }

    // ---- current readings -------------------------------------------------------------------------------------------------

    @Test
    fun staleReadingIsTreatedAsMissing() {
        val stale = context(reading("engine.coolant_temp", 90.0), at = 80.seconds)
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.COOLANT_TEMPERATURE, stale).key)
        val borderline = context(reading("engine.coolant_temp", 90.0), at = 90.seconds)
        assertEquals(AnswerKeys.COOLANT_NORMAL, answerOf(Intent.COOLANT_TEMPERATURE, borderline).key)
        val justStale = context(reading("engine.coolant_temp", 90.0), at = 89.seconds)
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.COOLANT_TEMPERATURE, justStale).key)
        val lenient = context(reading("engine.coolant_temp", 90.0), at = 80.seconds, maxAge = { Duration.INFINITE })
        assertEquals(AnswerKeys.COOLANT_NORMAL, answerOf(Intent.COOLANT_TEMPERATURE, lenient).key)
    }

    @Test
    fun outOfRangeReadingIsTreatedAsMissing() {
        val ctx = context(reading("engine.coolant_temp", 300.0, inRange = false))
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.COOLANT_TEMPERATURE, ctx).key)
    }

    @Test
    fun maxAgeIsPerSignal() {
        val ctx = context(
            reading("engine.coolant_temp", 90.0),
            reading("fuel.level_pct", 50.0),
            at = 90.seconds,
            maxAge = { id -> if (id == "fuel.level_pct") 30.seconds else 5.seconds },
        )
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.COOLANT_TEMPERATURE, ctx).key)
        assertEquals(AnswerKeys.FUEL_LEVEL, answerOf(Intent.FUEL_LEVEL, ctx).key)
    }

    @Test
    fun untrustedReadingIsIgnoredByOrdinaryIntents() {
        val ctx = context(reading("engine.coolant_temp", 90.0), provenance = provenanceOf(mapOf("engine.coolant_temp" to Provenance.COMMUNITY)))
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.COOLANT_TEMPERATURE, ctx).key)
        assertEquals(
            AnswerKeys.VALUE_UNKNOWN,
            answerOf(
                Intent.COOLANT_TEMPERATURE,
                context(reading("engine.coolant_temp", 90.0), provenance = { _, _ ->
                    null
                }),
            ).key,
        )
    }

    // ---- temperatures -----------------------------------------------------------------------------------------------------

    @Test
    fun coolantMeanings() {
        fun key(c: Double) = answerOf(Intent.COOLANT_TEMPERATURE, context(reading("engine.coolant_temp", c))).key
        assertEquals(AnswerKeys.COOLANT_WARMING_UP, key(42.0))
        assertEquals(AnswerKeys.COOLANT_NORMAL, key(60.0))
        assertEquals(AnswerKeys.COOLANT_NORMAL, key(90.0))
        assertEquals(AnswerKeys.COOLANT_NORMAL, key(105.0))
        assertEquals(AnswerKeys.COOLANT_HOT, key(106.0))
        assertEquals(
            "The coolant is at 42 degrees, still warming up.",
            textOf(answerOf(Intent.COOLANT_TEMPERATURE, context(reading("engine.coolant_temp", 42.4)))),
        )
    }

    @Test
    fun coolantPolicyCanBeChanged() {
        val ctx = context(reading("engine.coolant_temp", 100.0))
        assertEquals(AnswerKeys.COOLANT_HOT, answerOf(Intent.COOLANT_TEMPERATURE, ctx, policy = AnswerPolicy(coolantHotAboveC = 95.0)).key)
    }

    @Test
    fun intakeUsesPid0fThenFallsBackToBank1Sensor1() {
        val both = context(reading("intake.air_temp", 25.0), reading("intake.air_temp_b1s1", 31.0))
        assertEquals(25.0, answerOf(Intent.INTAKE_TEMPERATURE, both).quantity("value"))
        val onlyB1s1 = context(reading("intake.air_temp_b1s1", 31.0))
        val answer = answerOf(Intent.INTAKE_TEMPERATURE, onlyB1s1)
        assertEquals(AnswerKeys.INTAKE_VALUE, answer.key)
        assertEquals(31.0, answer.quantity("value"))
        assertEquals("The intake air is at 31 degrees.", textOf(answer))
        // A stale first choice does not hide a current second one.
        val staleFirst = AnswerContext(
            both.snapshot.with(listOf(reading("intake.air_temp_b1s1", 33.0)), 99.seconds),
            100.seconds,
            LinkStatus.LIVE,
            ALL_STANDARD,
            { if (it == "intake.air_temp") (-1).seconds else 10.seconds },
        )
        assertEquals(33.0, answerOf(Intent.INTAKE_TEMPERATURE, staleFirst).quantity("value"))
    }

    @Test
    fun exhaustUsesEgtThenDpfInletTemperature() {
        val egt = context(reading("exhaust.egt_b1s1", 312.0), reading("dpf.inlet_temp", 280.0))
        assertEquals(312.0, answerOf(Intent.EXHAUST_TEMPERATURE, egt).quantity("value"))
        val inlet = context(reading("dpf.inlet_temp", 280.0))
        assertEquals(280.0, answerOf(Intent.EXHAUST_TEMPERATURE, inlet).quantity("value"))
        assertEquals("The exhaust is at 312 degrees.", textOf(answerOf(Intent.EXHAUST_TEMPERATURE, egt)))
    }

    @Test
    fun temperaturesListsWhatIsCurrent() {
        val all = context(reading("engine.coolant_temp", 90.0), reading("intake.air_temp", 31.0), reading("exhaust.egt_b1s1", 250.0))
        assertEquals("Coolant 90 degrees, intake air 31 degrees and exhaust 250 degrees.", textOf(answerOf(Intent.TEMPERATURES, all)))
        val two = context(reading("engine.coolant_temp", 90.0), reading("exhaust.egt_b1s1", 250.0))
        assertEquals("Coolant 90 degrees and exhaust 250 degrees.", textOf(answerOf(Intent.TEMPERATURES, two)))
        val one = context(reading("intake.air_temp_b1s1", -5.0))
        assertEquals("Intake air minus 5 degrees.", textOf(answerOf(Intent.TEMPERATURES, one)))
        val none = answerOf(Intent.TEMPERATURES, context())
        assertEquals(AnswerKeys.VALUE_UNKNOWN, none.key)
        assertEquals(AnswerKeys.TOPIC_TEMPERATURES, none.phraseKey("topic"))
    }

    // ---- levels -----------------------------------------------------------------------------------------------------------

    @Test
    fun fuelLevelAndLow() {
        assertEquals("The fuel level is 42 percent.", textOf(answerOf(Intent.FUEL_LEVEL, context(reading("fuel.level_pct", 42.0)))))
        assertEquals(AnswerKeys.FUEL_LEVEL, answerOf(Intent.FUEL_LEVEL, context(reading("fuel.level_pct", 15.0))).key)
        assertEquals(AnswerKeys.FUEL_LOW, answerOf(Intent.FUEL_LEVEL, context(reading("fuel.level_pct", 14.9))).key)
    }

    @Test
    fun adBluePrefersTheVerifiedReading() {
        val ctx = context(reading("def.level_pct", 60.0, ecu = "ecu2"), reading("def.reagent_level_pct", 20.0))
        val answer = answerOf(Intent.ADBLUE_LEVEL, ctx)
        assertEquals(AnswerKeys.ADBLUE_LEVEL, answer.key)
        assertEquals(60.0, answer.quantity("value"))
        assertEquals(AnswerKeys.ADBLUE_LOW, answerOf(Intent.ADBLUE_LEVEL, context(reading("def.level_pct", 10.0))).key)
        assertEquals("The AdBlue level is 60 percent.", textOf(answer))
    }

    @Test
    fun adBlueFallsBackToTheInferredReadingUnderAnUnconfirmedKey() {
        val provenance = provenanceOf(mapOf("def.reagent_level_pct" to Provenance.INFERRED, "def.level_pct" to Provenance.VERIFIED))
        val inferredOnly = context(reading("def.reagent_level_pct", 55.0), provenance = provenance)
        val answer = answerOf(Intent.ADBLUE_LEVEL, inferredOnly)
        assertEquals(AnswerKeys.ADBLUE_UNCONFIRMED, answer.key)
        assertEquals(55.0, answer.quantity("value"))
        assertEquals("The AdBlue reading is unconfirmed, but it suggests about 55 percent.", textOf(answer))
        val low = answerOf(Intent.ADBLUE_LEVEL, context(reading("def.reagent_level_pct", 9.0), provenance = provenance))
        assertEquals(AnswerKeys.ADBLUE_UNCONFIRMED_LOW, low.key)
        // A current verified reading wins over the inferred one.
        val both = context(reading("def.reagent_level_pct", 55.0), reading("def.level_pct", 70.0), provenance = provenance)
        assertEquals(AnswerKeys.ADBLUE_LEVEL, answerOf(Intent.ADBLUE_LEVEL, both).key)
        // A stale verified reading leaves the inferred fallback.
        val staleVerified = AnswerContext(
            both.snapshot,
            100.seconds,
            LinkStatus.LIVE,
            provenance,
            { if (it == "def.level_pct") (-1).seconds else 10.seconds },
        )
        assertEquals(AnswerKeys.ADBLUE_UNCONFIRMED, answerOf(Intent.ADBLUE_LEVEL, staleVerified).key)
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.ADBLUE_LEVEL, context()).key)
    }

    // ---- battery ----------------------------------------------------------------------------------------------------------

    private fun battery(volts: Double, rpm: Double?): Answer {
        val readings = listOfNotNull(reading("electrical.module_voltage", volts), rpm?.let { reading("engine.rpm", it) })
        return answerOf(Intent.BATTERY_VOLTAGE, context(*readings.toTypedArray()))
    }

    @Test
    fun batteryWithTheEngineRunning() {
        assertEquals(AnswerKeys.BATTERY_CHARGING_NORMALLY, battery(14.1, 900.0).key)
        assertEquals(AnswerKeys.BATTERY_CHARGING_NORMALLY, battery(13.2, 900.0).key)
        assertEquals(AnswerKeys.BATTERY_NOT_CHARGING, battery(12.5, 900.0).key)
        assertEquals(AnswerKeys.BATTERY_TOO_HIGH, battery(15.5, 900.0).key)
        assertEquals(AnswerKeys.BATTERY_VOLTAGE, battery(13.1, 900.0).key)
        assertEquals(AnswerKeys.BATTERY_VOLTAGE, battery(14.9, 900.0).key)
        assertEquals("The battery is at 14.1 volts, charging normally.", textOf(battery(14.12, 900.0)))
    }

    @Test
    fun batteryWithTheEngineOff() {
        assertEquals(AnswerKeys.BATTERY_HEALTHY, battery(12.6, 0.0).key)
        assertEquals(AnswerKeys.BATTERY_HEALTHY, battery(12.4, 0.0).key)
        assertEquals(AnswerKeys.BATTERY_A_BIT_LOW, battery(12.2, 0.0).key)
        assertEquals(AnswerKeys.BATTERY_LOW, battery(11.5, 0.0).key)
        // Cranking speeds count as off: rpm 400 is not above the running threshold.
        assertEquals(AnswerKeys.BATTERY_HEALTHY, battery(12.6, 400.0).key)
    }

    @Test
    fun batteryWithUnknownEngineStateJustSaysTheVoltage() {
        val answer = battery(12.6, null)
        assertEquals(AnswerKeys.BATTERY_VOLTAGE, answer.key)
        assertEquals("The battery is at 12.6 volts.", textOf(answer))
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.BATTERY_VOLTAGE, context()).key)
    }

    // ---- speed and rpm ----------------------------------------------------------------------------------------------------

    @Test
    fun speedAndRpm() {
        assertEquals("You're doing 25 kilometres per hour.", textOf(answerOf(Intent.SPEED, context(reading("vehicle.speed", 25.2)))))
        assertEquals(AnswerKeys.SPEED_STOPPED, answerOf(Intent.SPEED, context(reading("vehicle.speed", 0.0), reading("engine.rpm", 800.0))).key)
        assertEquals(AnswerKeys.SPEED_STOPPED, answerOf(Intent.SPEED, context(reading("vehicle.speed", 0.0))).key)
        assertEquals(AnswerKeys.ENGINE_OFF, answerOf(Intent.SPEED, context(reading("vehicle.speed", 0.0), reading("engine.rpm", 0.0))).key)
        assertEquals(AnswerKeys.ENGINE_OFF, answerOf(Intent.SPEED, context(reading("engine.rpm", 0.0))).key)
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.SPEED, context(reading("engine.rpm", 900.0))).key)
        assertEquals("The engine is at 1970 rpm.", textOf(answerOf(Intent.RPM, context(reading("engine.rpm", 1968.0)))))
        assertEquals("The engine is off.", textOf(answerOf(Intent.RPM, context(reading("engine.rpm", 0.0)))))
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.RPM, context()).key)
    }

    // ---- DPF --------------------------------------------------------------------------------------------------------------

    @Test
    fun dpfRegenerationStatusBits() {
        assertEquals(false, isDpfRegenerationActive(0.0))
        assertEquals(true, isDpfRegenerationActive(1.0))
        assertEquals(true, isDpfRegenerationActive(2.0))
        assertEquals(true, isDpfRegenerationActive(3.0))
        assertEquals(false, isDpfRegenerationActive(4.0))
        assertEquals(true, isDpfRegenerationActive(6.0))
    }

    @Test
    fun dpfRegenerating() {
        val answer = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 62.0), reading("dpf.regen_status", 2.0)))
        assertEquals(AnswerKeys.DPF_REGENERATING, answer.key)
        assertEquals("The DPF is regenerating, soot at 62 percent. Keep driving if you can until it finishes.", textOf(answer))
    }

    @Test
    fun dpfNotRegenerating() {
        val answer = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 17.0), reading("dpf.regen_status", 0.0)))
        assertEquals("Soot load is 17 percent, no regeneration running.", textOf(answer))
        val due = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 85.0), reading("dpf.regen_status", 0.0)))
        assertEquals(AnswerKeys.DPF_IDLE_DUE, due.key)
        assertEquals("Soot load is 85 percent, no regeneration running, but a regeneration is due soon.", textOf(due))
        assertEquals(AnswerKeys.DPF_IDLE, answerOf(Intent.DPF, context(reading("dpf.soot_pct", 79.0), reading("dpf.regen_status", 0.0))).key)
    }

    @Test
    fun dpfWithOnlyOneOfTheTwoReadings() {
        val sootOnly = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 40.0)))
        assertEquals("Soot load is 40 percent, but I can't tell whether it is regenerating.", textOf(sootOnly))
        assertEquals(AnswerKeys.DPF_REGEN_ONLY_ACTIVE, answerOf(Intent.DPF, context(reading("dpf.regen_status", 2.0))).key)
        assertEquals(AnswerKeys.DPF_REGEN_ONLY_IDLE, answerOf(Intent.DPF, context(reading("dpf.regen_status", 0.0))).key)
    }

    @Test
    fun dpfIgnoresUntrustedReadings() {
        val inferredRegen = provenanceOf(mapOf("dpf.regen_status" to Provenance.INFERRED))
        val answer = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 40.0), reading("dpf.regen_status", 2.0), provenance = inferredRegen))
        assertEquals(AnswerKeys.DPF_SOOT_ONLY, answer.key)
        val inferredSoot = provenanceOf(mapOf("dpf.soot_pct" to Provenance.COMMUNITY))
        val regenOnly = answerOf(Intent.DPF, context(reading("dpf.soot_pct", 40.0), reading("dpf.regen_status", 2.0), provenance = inferredSoot))
        assertEquals(AnswerKeys.DPF_REGEN_ONLY_ACTIVE, regenOnly.key)
        // The pressure difference is inferred and never used.
        assertEquals(AnswerKeys.VALUE_UNKNOWN, answerOf(Intent.DPF, context(reading("dpf.delta_pressure_kpa", 5.0))).key)
    }

    // ---- warnings ---------------------------------------------------------------------------------------------------------

    private val mil = { on: Double -> reading("diag.mil_on", on) }
    private val dtc = { n: Double -> reading("diag.dtc_count", n) }

    @Test
    fun warningsActiveAndMonitoring() {
        val two = FakeWarnings(true, listOf(warning("a", key = AnswerKeys.TOPIC_FUEL), warning("b")))
        val answer = answerOf(Intent.WARNINGS, context(mil(0.0)), two)
        assertEquals(AnswerKeys.WARNINGS_MANY, answer.key)
        assertEquals("There are 2 warnings. The most important: fuel level.", textOf(answer))
        val one = answerOf(Intent.WARNINGS, context(mil(0.0)), FakeWarnings(true, listOf(warning("a", key = AnswerKeys.TOPIC_FUEL))))
        assertEquals("There is a warning: fuel level.", textOf(one))
    }

    @Test
    fun warningsWithTheEngineLightOn() {
        val withWarning = answerOf(Intent.WARNINGS, context(mil(1.0), dtc(3.0)), FakeWarnings(true, listOf(warning("a", key = AnswerKeys.TOPIC_FUEL))))
        assertEquals("There is a warning: fuel level. Also, the check engine light is on, with 3 fault codes stored.", textOf(withWarning))
        val alone = answerOf(Intent.WARNINGS, context(mil(1.0), dtc(1.0)), FakeWarnings(true))
        assertEquals("There are no active warnings, but the check engine light is on, with one fault code stored.", textOf(alone))
        val noCount = answerOf(Intent.WARNINGS, context(mil(1.0)), FakeWarnings(true))
        assertEquals("There are no active warnings, but the check engine light is on.", textOf(noCount))
    }

    @Test
    fun warningsNoneAndEngineLightOff() {
        val answer = answerOf(Intent.WARNINGS, context(mil(0.0)), FakeWarnings(true))
        assertEquals("No warnings, and the engine warning light is off.", textOf(answer))
        val unknown = answerOf(Intent.WARNINGS, context(), FakeWarnings(true))
        assertEquals(AnswerKeys.WARNINGS_NONE_MIL_UNKNOWN, unknown.key)
    }

    @Test
    fun warningsNeverClaimNoneWhenNothingIsMonitoring() {
        val off = answerOf(Intent.WARNINGS, context(mil(0.0)), FakeWarnings(false))
        assertEquals(AnswerKeys.WARNINGS_UNMONITORED_MIL_OFF, off.key)
        assertEquals("The warning checks aren't running yet, but the engine warning light is off.", textOf(off))
        val on = answerOf(Intent.WARNINGS, context(mil(1.0), dtc(2.0)), FakeWarnings(false))
        assertEquals("The warning checks aren't running yet, and the check engine light is on, with 2 fault codes stored.", textOf(on))
        val unknown = answerOf(Intent.WARNINGS, context(), NoWarningsSource)
        assertEquals(AnswerKeys.WARNINGS_UNMONITORED_MIL_UNKNOWN, unknown.key)
        // An untrusted engine light reading counts as not readable.
        val untrusted = answerOf(Intent.WARNINGS, context(mil(1.0), provenance = provenanceOf(mapOf("diag.mil_on" to Provenance.INFERRED))), FakeWarnings(true))
        assertEquals(AnswerKeys.WARNINGS_NONE_MIL_UNKNOWN, untrusted.key)
    }

    // ---- switch off -------------------------------------------------------------------------------------------------------

    private fun switchOff(vararg readings: Reading, warnings: WarningsSource = FakeWarnings(), provenance: SignalProvenance = ALL_STANDARD): Answer =
        answerOf(Intent.SWITCH_OFF, context(*readings, provenance = provenance), warnings)

    private val calm =
        arrayOf(reading("vehicle.speed", 0.0), reading("dpf.regen_status", 0.0), reading("dpf.soot_pct", 30.0), reading("exhaust.egt_b1s1", 150.0))

    @Test
    fun switchOffYes() {
        val monitored = switchOff(*calm)
        assertEquals(AnswerKeys.SWITCH_OFF_YES, monitored.key)
        assertEquals("Yes, it's fine to switch off.", textOf(monitored))
        val unmonitored = switchOff(*calm, warnings = NoWarningsSource)
        assertEquals(AnswerKeys.SWITCH_OFF_YES_UNMONITORED, unmonitored.key)
        assertEquals("No regeneration is running, so it's fine to switch off.", textOf(unmonitored))
    }

    @Test
    fun switchOffYesWithoutExhaustReading() {
        assertEquals(AnswerKeys.SWITCH_OFF_YES, switchOff(reading("vehicle.speed", 0.0), reading("dpf.regen_status", 0.0)).key)
    }

    @Test
    fun switchOffNeverYesWithoutATrustedCurrentSpeed() {
        val answer = switchOff(reading("dpf.regen_status", 0.0))
        assertEquals(AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN, answer.key)
        assertEquals("No regeneration is running, but I can't see the speed: switch off once you've stopped.", textOf(answer))
        val inferred = switchOff(*calm, provenance = provenanceOf(mapOf("vehicle.speed" to Provenance.INFERRED)))
        assertEquals(AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN, inferred.key)
        val staleSpeed = AnswerContext(
            context(*calm).snapshot,
            100.seconds,
            LinkStatus.LIVE,
            ALL_STANDARD,
            { if (it == "vehicle.speed") (-1).seconds else 10.seconds },
        )
        assertEquals(AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN, answerOf(Intent.SWITCH_OFF, staleSpeed).key)
    }

    @Test
    fun switchOffNotWhileMoving() {
        val answer = switchOff(reading("vehicle.speed", 12.0), reading("dpf.regen_status", 0.0))
        assertEquals("You're still moving.", textOf(answer))
        // An untrusted speed does not block (only trusted current readings are used) but it never allows a yes either.
        val untrustedSpeed = switchOff(
            reading("vehicle.speed", 12.0),
            reading("dpf.regen_status", 0.0),
            provenance = provenanceOf(
                mapOf(
                    "vehicle.speed" to Provenance.INFERRED,
                ),
            ),
        )
        assertEquals(AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN, untrustedSpeed.key)
    }

    @Test
    fun switchOffNeverYesWithoutATrustedCurrentRegenStatus() {
        for (provenance in listOf(Provenance.INFERRED, Provenance.COMMUNITY)) {
            val answer = switchOff(*calm, provenance = provenanceOf(mapOf("dpf.regen_status" to provenance)))
            assertEquals(AnswerKeys.SWITCH_OFF_UNKNOWN, answer.key, "$provenance")
        }
        assertEquals(AnswerKeys.SWITCH_OFF_UNKNOWN, switchOff().key)
        assertEquals(AnswerKeys.SWITCH_OFF_UNKNOWN, switchOff(reading("dpf.regen_status", 0.0, inRange = false)).key)
        assertEquals(AnswerKeys.SWITCH_OFF_UNKNOWN, switchOff(provenance = { _, _ -> null }).key)
        val stale = answerOf(Intent.SWITCH_OFF, context(*calm, at = 50.seconds), FakeWarnings())
        assertEquals(AnswerKeys.SWITCH_OFF_UNKNOWN, stale.key)
        assertEquals("I can't confirm: I don't have a current DPF reading.", textOf(stale))
    }

    @Test
    fun switchOffNotWhileRegenerating() {
        val answer = switchOff(reading("dpf.regen_status", 2.0), reading("dpf.soot_pct", 55.0))
        assertEquals("Not yet: the DPF is regenerating (soot 55 percent). Keep the engine running until it finishes.", textOf(answer))
        assertEquals(AnswerKeys.SWITCH_OFF_REGENERATING_NO_SOOT, switchOff(reading("dpf.regen_status", 1.0)).key)
    }

    @Test
    fun switchOffAfterAHotExhaustWaitsForIt() {
        val answer = switchOff(reading("dpf.regen_status", 0.0), reading("exhaust.egt_b1s1", 520.0))
        assertEquals("Let it idle for a minute first: the exhaust is at 520 degrees.", textOf(answer))
        assertEquals(
            AnswerKeys.SWITCH_OFF_YES,
            switchOff(reading("vehicle.speed", 0.0), reading("dpf.regen_status", 0.0), reading("exhaust.egt_b1s1", 450.0)).key,
        )
        val custom =
            answerOf(
                Intent.SWITCH_OFF,
                context(reading("dpf.regen_status", 0.0), reading("exhaust.egt_b1s1", 350.0)),
                FakeWarnings(),
                AnswerPolicy(hotExhaustC = 300.0),
            )
        assertEquals(AnswerKeys.SWITCH_OFF_EXHAUST_HOT, custom.key)
        // Inferred exhaust readings are not used.
        val inferred = switchOff(
            reading("vehicle.speed", 0.0),
            reading("dpf.regen_status", 0.0),
            reading("exhaust.egt_b1s1", 520.0),
            provenance = provenanceOf(
                mapOf(
                    "exhaust.egt_b1s1" to Provenance.INFERRED,
                ),
            ),
        )
        assertEquals(AnswerKeys.SWITCH_OFF_YES, inferred.key)
    }

    @Test
    fun switchOffBlockedByAWarning() {
        val blocking = FakeWarnings(true, listOf(warning("w1"), warning("w2", blocksSwitchOff = true, key = AnswerKeys.TOPIC_FUEL)))
        val answer = switchOff(*calm, warnings = blocking)
        assertEquals(AnswerKeys.SWITCH_OFF_BLOCKED_BY_WARNING, answer.key)
        assertEquals("Not yet: fuel level.", textOf(answer))
        // A warning that does not block leaves the answer alone.
        assertEquals(AnswerKeys.SWITCH_OFF_YES, switchOff(*calm, warnings = FakeWarnings(true, listOf(warning("w1")))).key)
    }

    @Test
    fun switchOffOrderIsLinkThenWarningThenMovingThenRegenThenExhaust() {
        val blocking = FakeWarnings(true, listOf(warning("w", blocksSwitchOff = true)))
        val everythingWrong = arrayOf(reading("vehicle.speed", 30.0), reading("dpf.regen_status", 2.0), reading("exhaust.egt_b1s1", 600.0))
        assertEquals(AnswerKeys.LINK_RECONNECTING, answerOf(Intent.SWITCH_OFF, context(*everythingWrong, link = LinkStatus.RECONNECTING), blocking).key)
        assertEquals(AnswerKeys.SWITCH_OFF_BLOCKED_BY_WARNING, switchOff(*everythingWrong, warnings = blocking).key)
        assertEquals(AnswerKeys.SWITCH_OFF_MOVING, switchOff(*everythingWrong).key)
        assertEquals(AnswerKeys.SWITCH_OFF_REGENERATING_NO_SOOT, switchOff(everythingWrong[1], everythingWrong[2]).key)
        assertEquals(AnswerKeys.SWITCH_OFF_EXHAUST_HOT, switchOff(reading("dpf.regen_status", 0.0), everythingWrong[2]).key)
    }

    // ---- status -----------------------------------------------------------------------------------------------------------

    @Test
    fun statusIsOneSentence() {
        val ctx = context(reading("engine.coolant_temp", 90.0), reading("fuel.level_pct", 60.0), mil(0.0))
        val answer = answerOf(Intent.STATUS, ctx, FakeWarnings(true))
        assertEquals("I'm connected, and there are no warnings and the engine temperature is normal.", textOf(answer))
    }

    @Test
    fun statusReportsRegenWarningsAndLowFuel() {
        val ctx = context(
            reading("engine.coolant_temp", 110.0),
            reading("fuel.level_pct", 9.0),
            reading("dpf.regen_status", 2.0),
            mil(1.0),
        )
        val answer = answerOf(Intent.STATUS, ctx, FakeWarnings(true, listOf(warning("a"), warning("b"))))
        assertEquals(
            "I'm connected, and there are 2 active warnings, the check engine light is on, the DPF is regenerating, the engine is running hot and the fuel is low.",
            textOf(answer),
        )
    }

    @Test
    fun statusWithoutMonitoringNeverClaimsNoWarnings() {
        val answer = answerOf(Intent.STATUS, context(reading("engine.coolant_temp", 45.0)), NoWarningsSource)
        assertEquals("I'm connected, and the warning checks aren't running yet and the engine is still warming up.", textOf(answer))
    }

    @Test
    fun statusIgnoresUntrustedReadings() {
        val ctx = context(
            reading("engine.coolant_temp", 110.0),
            reading("dpf.regen_status", 2.0),
            provenance = provenanceOf(mapOf("engine.coolant_temp" to Provenance.INFERRED, "dpf.regen_status" to Provenance.COMMUNITY)),
        )
        val answer = answerOf(Intent.STATUS, ctx, FakeWarnings(true))
        assertEquals("I'm connected, and there are no warnings and I have no current readings.", textOf(answer))
    }

    @Test
    fun statusSingularWarning() {
        val answer = answerOf(Intent.STATUS, context(reading("engine.coolant_temp", 90.0)), FakeWarnings(true, listOf(warning("a"))))
        assertEquals("I'm connected, and there is one active warning and the engine temperature is normal.", textOf(answer))
    }
}
