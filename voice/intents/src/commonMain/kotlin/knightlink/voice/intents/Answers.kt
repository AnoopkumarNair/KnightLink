package knightlink.voice.intents

import knightlink.core.telemetry.TimedReading
import kotlin.math.roundToInt

/**
 * The numbers behind the meanings answers give ("running hot", "low"). One place, with defaults, so they can be tuned or
 * overridden per vehicle without touching the answer logic.
 */
public data class AnswerPolicy(
    /** Coolant below this (degC) is "still warming up". */
    val coolantWarmBelowC: Double = 60.0,
    /** Coolant above this (degC) is "running hot". */
    val coolantHotAboveC: Double = 105.0,
    /** Fuel below this (percent) is "low". */
    val lowFuelPct: Double = 15.0,
    /** AdBlue below this (percent) is "low". */
    val lowAdBluePct: Double = 15.0,
    /** Above this engine speed (rpm) the engine counts as running when judging the battery voltage. */
    val engineRunningRpm: Double = 400.0,
    /** Engine running: this range (V) is "charging normally". */
    val chargingMinV: Double = 13.2,
    val chargingMaxV: Double = 14.8,
    /** Engine running: below this (V) the battery is "not charging properly". Between this and [chargingMinV] only the voltage is said. */
    val notChargingBelowV: Double = 13.0,
    /** Engine running: above this (V) the voltage is "too high". Between [chargingMaxV] and this only the voltage is said. */
    val tooHighAboveV: Double = 15.0,
    /** Engine off: at or above this (V) the battery is "healthy". */
    val restingHealthyMinV: Double = 12.4,
    /** Engine off: below this (V) the battery is "low"; between this and [restingHealthyMinV] it is "a bit low". */
    val restingLowBelowV: Double = 12.0,
    /** Soot at or above this (percent) means "a regeneration is due soon". */
    val dpfDueSootPct: Double = 80.0,
    /** Exhaust above this (degC) means "let it idle first" before switching off. */
    val hotExhaustC: Double = 450.0,
)

/**
 * True when the DPF regeneration status (signal `dpf.regen_status`, the status byte of the DPF report) says a regeneration is going on.
 * Conservative on purpose: bit 0 ("in progress") or bit 1 ("active type") set counts as regenerating, since a false "not regenerating"
 * could get the engine switched off mid-regeneration while a false "regenerating" only delays switching off.
 */
public fun isDpfRegenerationActive(status: Double): Boolean = (status.toInt() and REGEN_ACTIVE_BITS) != 0

private const val REGEN_ACTIVE_BITS = 0x03

/**
 * Turns the current facts into one spoken sentence, as an [Answer]. Pure: the same inputs always give the same answer.
 *
 * A reading is current iff the link is LIVE, it exists, it is no older than the context's max age for its signal, and it is in
 * range. Safety intents (STATUS, DPF, WARNINGS, SWITCH_OFF) use only current readings whose provenance is trusted; the other intents
 * ignore untrusted readings too, except AdBlue, which falls back to an unconfirmed reading under a key that says so.
 */
public object Answers {
    /**
     * @param warnings the active warnings, from the rules engine
     * @param policy thresholds for the meanings
     * @throws IllegalArgumentException for [Intent.REPEAT] and [Intent.STOP]: they are about speaking, not about the car, and
     *   [VoiceAssistant] handles them
     */
    public fun answer(intent: Intent, context: AnswerContext, warnings: WarningsSource, policy: AnswerPolicy = AnswerPolicy()): Answer {
        require(intent != Intent.REPEAT && intent != Intent.STOP) { "$intent is handled by the assistant, not answered from telemetry" }
        if (intent == Intent.UNKNOWN) return Answer(AnswerKeys.HELP)
        linkAnswer(context.link)?.let { return it }
        return Builder(Facts(context), warnings, policy).build(intent)
    }

    private fun linkAnswer(link: LinkStatus): Answer? = when (link) {
        LinkStatus.LIVE -> null
        LinkStatus.CONNECTING -> Answer(AnswerKeys.LINK_CONNECTING)
        LinkStatus.WAITING_FOR_IGNITION -> Answer(AnswerKeys.LINK_WAITING_FOR_IGNITION)
        LinkStatus.RECONNECTING -> Answer(AnswerKeys.LINK_RECONNECTING)
        LinkStatus.NOT_CONNECTED -> Answer(AnswerKeys.LINK_NOT_CONNECTED)
    }
}

private const val SOOT = "dpf.soot_pct"
private const val REGEN = "dpf.regen_status"
private const val COOLANT = "engine.coolant_temp"
private const val FUEL = "fuel.level_pct"
private const val VOLTAGE = "electrical.module_voltage"
private const val SPEED = "vehicle.speed"
private const val RPM = "engine.rpm"
private const val MIL = "diag.mil_on"
private const val DTC_COUNT = "diag.dtc_count"
private const val DEF_LEVEL = "def.level_pct"
private const val DEF_REAGENT_LEVEL = "def.reagent_level_pct"
private val INTAKE_IDS = listOf("intake.air_temp", "intake.air_temp_b1s1")
private val EXHAUST_IDS = listOf("exhaust.egt_b1s1", "dpf.inlet_temp")

private fun quantity(value: Double, unit: String): AnswerArg = AnswerArg.Quantity(value, unit)

private fun phrase(key: String): AnswerArg = AnswerArg.Phrase(Answer(key))

/** The readings of one question, judged current and trusted. */
private class Facts(private val context: AnswerContext) {
    private fun isCurrent(id: String, reading: TimedReading): Boolean =
        context.now - reading.at <= context.maxAge(id) && reading.reading.signal.inRange

    /** The current reading of [id] from any ECU, trusted or not. */
    fun current(id: String): TimedReading? {
        if (context.link != LinkStatus.LIVE) return null
        return context.snapshot.reading(id)?.takeIf { isCurrent(id, it) }
    }

    fun isTrusted(id: String, reading: TimedReading): Boolean = context.provenance.of(id, reading.reading.ecu).isTrusted()

    /** The value of [id] when its reading is current and trusted. */
    fun trusted(id: String): Double? =
        current(id)?.takeIf { isTrusted(id, it) }?.reading?.signal?.value

    /** The first of [ids] with a trusted current value. */
    fun firstTrusted(ids: List<String>): Double? = ids.firstNotNullOfOrNull { trusted(it) }

    fun milOn(): Boolean? = trusted(MIL)?.let { it >= 0.5 }

    fun regenerating(): Boolean? = trusted(REGEN)?.let(::isDpfRegenerationActive)
}

private class Builder(private val facts: Facts, private val warnings: WarningsSource, private val policy: AnswerPolicy) {
    fun build(intent: Intent): Answer = when (intent) {
        Intent.STATUS -> status()
        Intent.DPF -> dpf()
        Intent.TEMPERATURES -> temperatures()
        Intent.COOLANT_TEMPERATURE -> coolant()
        Intent.INTAKE_TEMPERATURE -> single(facts.firstTrusted(INTAKE_IDS), AnswerKeys.INTAKE_VALUE, AnswerKeys.TOPIC_INTAKE)
        Intent.EXHAUST_TEMPERATURE -> single(facts.firstTrusted(EXHAUST_IDS), AnswerKeys.EXHAUST_VALUE, AnswerKeys.TOPIC_EXHAUST)
        Intent.FUEL_LEVEL -> fuel()
        Intent.ADBLUE_LEVEL -> adBlue()
        Intent.BATTERY_VOLTAGE -> battery()
        Intent.SPEED -> speed()
        Intent.RPM -> rpm()
        Intent.WARNINGS -> warningsAnswer()
        Intent.SWITCH_OFF -> switchOff()
        Intent.UNKNOWN -> Answer(AnswerKeys.HELP)
        Intent.REPEAT, Intent.STOP -> throw IllegalArgumentException("$intent is not answered from telemetry")
    }

    private fun unknown(topicKey: String) = Answer(AnswerKeys.VALUE_UNKNOWN, mapOf("topic" to phrase(topicKey)))

    private fun single(value: Double?, key: String, topicKey: String): Answer =
        if (value == null) unknown(topicKey) else Answer(key, mapOf("value" to quantity(value, "degC")))

    private fun coolantKey(value: Double): String = when {
        value < policy.coolantWarmBelowC -> AnswerKeys.COOLANT_WARMING_UP
        value > policy.coolantHotAboveC -> AnswerKeys.COOLANT_HOT
        else -> AnswerKeys.COOLANT_NORMAL
    }

    private fun coolant(): Answer {
        val value = facts.trusted(COOLANT) ?: return unknown(AnswerKeys.TOPIC_COOLANT)
        return Answer(coolantKey(value), mapOf("value" to quantity(value, "degC")))
    }

    private fun temperatures(): Answer {
        val parts = ArrayList<Answer>()
        facts.trusted(COOLANT)?.let { parts += Answer(AnswerKeys.TEMPERATURE_PART_COOLANT, mapOf("value" to quantity(it, "degC"))) }
        facts.firstTrusted(INTAKE_IDS)?.let { parts += Answer(AnswerKeys.TEMPERATURE_PART_INTAKE, mapOf("value" to quantity(it, "degC"))) }
        facts.firstTrusted(EXHAUST_IDS)?.let { parts += Answer(AnswerKeys.TEMPERATURE_PART_EXHAUST, mapOf("value" to quantity(it, "degC"))) }
        if (parts.isEmpty()) return unknown(AnswerKeys.TOPIC_TEMPERATURES)
        return Answer(AnswerKeys.TEMPERATURES_LIST, mapOf("parts" to AnswerArg.Phrases(parts)))
    }

    private fun fuel(): Answer {
        val value = facts.trusted(FUEL) ?: return unknown(AnswerKeys.TOPIC_FUEL)
        val key = if (value < policy.lowFuelPct) AnswerKeys.FUEL_LOW else AnswerKeys.FUEL_LEVEL
        return Answer(key, mapOf("value" to quantity(value, "percent")))
    }

    private fun adBlue(): Answer {
        val candidates = listOf(DEF_LEVEL, DEF_REAGENT_LEVEL).mapNotNull { id ->
            facts.current(id)?.let { id to it }
        }
        val trusted = candidates.firstOrNull { (id, reading) -> facts.isTrusted(id, reading) }
        val (confirmed, chosen) = if (trusted != null) true to trusted else false to (candidates.firstOrNull() ?: return unknown(AnswerKeys.TOPIC_ADBLUE))
        val value = chosen.second.reading.signal.value
        val low = value < policy.lowAdBluePct
        val key = when {
            confirmed -> if (low) AnswerKeys.ADBLUE_LOW else AnswerKeys.ADBLUE_LEVEL
            else -> if (low) AnswerKeys.ADBLUE_UNCONFIRMED_LOW else AnswerKeys.ADBLUE_UNCONFIRMED
        }
        return Answer(key, mapOf("value" to quantity(value, "percent")))
    }

    private fun battery(): Answer {
        val volts = facts.trusted(VOLTAGE) ?: return unknown(AnswerKeys.TOPIC_BATTERY)
        val rpm = facts.trusted(RPM)
        val key = when {
            rpm == null -> AnswerKeys.BATTERY_VOLTAGE
            rpm > policy.engineRunningRpm -> when {
                volts < policy.notChargingBelowV -> AnswerKeys.BATTERY_NOT_CHARGING
                volts > policy.tooHighAboveV -> AnswerKeys.BATTERY_TOO_HIGH
                volts in policy.chargingMinV..policy.chargingMaxV -> AnswerKeys.BATTERY_CHARGING_NORMALLY
                else -> AnswerKeys.BATTERY_VOLTAGE
            }
            else -> when {
                volts >= policy.restingHealthyMinV -> AnswerKeys.BATTERY_HEALTHY
                volts >= policy.restingLowBelowV -> AnswerKeys.BATTERY_A_BIT_LOW
                else -> AnswerKeys.BATTERY_LOW
            }
        }
        return Answer(key, mapOf("value" to quantity(volts, "V")))
    }

    private fun speed(): Answer {
        val speed = facts.trusted(SPEED)
        val rpm = facts.trusted(RPM)
        val engineOff = rpm != null && rpm <= 0.0
        return when {
            speed == null -> if (engineOff) Answer(AnswerKeys.ENGINE_OFF) else unknown(AnswerKeys.TOPIC_SPEED)
            speed <= 0.0 -> Answer(if (engineOff) AnswerKeys.ENGINE_OFF else AnswerKeys.SPEED_STOPPED)
            else -> Answer(AnswerKeys.SPEED_VALUE, mapOf("value" to quantity(speed, "km/h")))
        }
    }

    private fun rpm(): Answer {
        val rpm = facts.trusted(RPM) ?: return unknown(AnswerKeys.TOPIC_RPM)
        return if (rpm <= 0.0) Answer(AnswerKeys.ENGINE_OFF) else Answer(AnswerKeys.RPM_VALUE, mapOf("value" to quantity(rpm, "rpm")))
    }

    private fun dpf(): Answer {
        val soot = facts.trusted(SOOT)
        val regen = facts.regenerating()
        val sootArg = soot?.let { mapOf("soot" to quantity(it, "percent")) } ?: emptyMap()
        return when {
            soot != null && regen == true -> Answer(AnswerKeys.DPF_REGENERATING, sootArg)
            soot != null && regen == false -> Answer(if (soot >= policy.dpfDueSootPct) AnswerKeys.DPF_IDLE_DUE else AnswerKeys.DPF_IDLE, sootArg)
            soot != null -> Answer(AnswerKeys.DPF_SOOT_ONLY, sootArg)
            regen == true -> Answer(AnswerKeys.DPF_REGEN_ONLY_ACTIVE)
            regen == false -> Answer(AnswerKeys.DPF_REGEN_ONLY_IDLE)
            else -> unknown(AnswerKeys.TOPIC_DPF)
        }
    }

    /** The engine light as a phrase of a longer answer, or null when it is not on. */
    private fun milOnPhrase(): Answer? {
        if (facts.milOn() != true) return null
        val count = facts.trusted(DTC_COUNT)?.roundToInt()
        return when {
            count == null || count < 1 -> Answer(AnswerKeys.MIL_ON_NO_COUNT)
            count == 1 -> Answer(AnswerKeys.MIL_ON_ONE_CODE)
            else -> Answer(AnswerKeys.MIL_ON, mapOf("count" to AnswerArg.Count(count)))
        }
    }

    private fun warningsAnswer(): Answer {
        val mil = milOnPhrase()
        val milState = facts.milOn()
        if (!warnings.isMonitoring) {
            // An empty list from a source that does not watch means nothing: never claim "no warnings".
            return when {
                mil != null -> Answer(AnswerKeys.WARNINGS_UNMONITORED_MIL_ON, mapOf("mil" to AnswerArg.Phrase(mil)))
                milState == false -> Answer(AnswerKeys.WARNINGS_UNMONITORED_MIL_OFF)
                else -> Answer(AnswerKeys.WARNINGS_UNMONITORED_MIL_UNKNOWN)
            }
        }
        val active = warnings.activeWarnings()
        if (active.isNotEmpty()) {
            val first = AnswerArg.Phrase(active.first().message)
            val head = if (active.size == 1) {
                Answer(AnswerKeys.WARNINGS_ONE, mapOf("message" to first))
            } else {
                Answer(AnswerKeys.WARNINGS_MANY, mapOf("count" to AnswerArg.Count(active.size), "message" to first))
            }
            return if (mil == null) head else Answer(AnswerKeys.WARNINGS_AND_MIL, mapOf("warnings" to AnswerArg.Phrase(head), "mil" to AnswerArg.Phrase(mil)))
        }
        return when {
            mil != null -> Answer(AnswerKeys.WARNINGS_NONE_MIL_ON, mapOf("mil" to AnswerArg.Phrase(mil)))
            milState == false -> Answer(AnswerKeys.WARNINGS_NONE)
            else -> Answer(AnswerKeys.WARNINGS_NONE_MIL_UNKNOWN)
        }
    }

    /**
     * Whether the engine can be switched off, decided in this order, first match wins:
     * 1. the link is not live (checked before): the link answer;
     * 2. an active warning that blocks switching off: no, with its message;
     * 3. the car is moving (speed current, trusted, above 0): no;
     * 4. no current trusted regeneration status: "can't confirm" (never a yes);
     * 5. regenerating: no, keep the engine running;
     * 6. the exhaust is hotter than the policy's limit: let it idle first;
     * 7. no current trusted speed reading: not a yes, switch off once stopped;
     * 8. yes (a different key when nothing else is being watched). A yes needs a trusted current speed of 0.
     */
    private fun switchOff(): Answer {
        warnings.activeWarnings().firstOrNull { it.blocksSwitchOff }?.let {
            return Answer(AnswerKeys.SWITCH_OFF_BLOCKED_BY_WARNING, mapOf("warning" to AnswerArg.Phrase(it.message)))
        }
        val speed = facts.trusted(SPEED)
        if (speed != null && speed > 0.0) return Answer(AnswerKeys.SWITCH_OFF_MOVING)
        val regen = facts.regenerating() ?: return Answer(AnswerKeys.SWITCH_OFF_UNKNOWN)
        if (regen) {
            val soot = facts.trusted(SOOT)
            return if (soot != null) {
                Answer(AnswerKeys.SWITCH_OFF_REGENERATING, mapOf("soot" to quantity(soot, "percent")))
            } else {
                Answer(AnswerKeys.SWITCH_OFF_REGENERATING_NO_SOOT)
            }
        }
        val exhaust = facts.firstTrusted(EXHAUST_IDS)
        if (exhaust != null && exhaust > policy.hotExhaustC) return Answer(AnswerKeys.SWITCH_OFF_EXHAUST_HOT, mapOf("value" to quantity(exhaust, "degC")))
        if (speed == null) return Answer(AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN)
        return Answer(if (warnings.isMonitoring) AnswerKeys.SWITCH_OFF_YES else AnswerKeys.SWITCH_OFF_YES_UNMONITORED)
    }

    /** One sentence from the headline facts: warnings, an active regeneration, how warm the engine is, low fuel. */
    private fun status(): Answer {
        val parts = ArrayList<Answer>()
        val milOn = facts.milOn()
        if (!warnings.isMonitoring) {
            parts += Answer(AnswerKeys.STATUS_PART_UNMONITORED)
        } else {
            val active = warnings.activeWarnings()
            when {
                active.size == 1 -> parts += Answer(AnswerKeys.STATUS_PART_WARNING_ONE)
                active.size > 1 -> parts += Answer(AnswerKeys.STATUS_PART_WARNINGS_MANY, mapOf("count" to AnswerArg.Count(active.size)))
                milOn != true -> parts += Answer(AnswerKeys.STATUS_PART_NO_WARNINGS)
            }
        }
        if (milOn == true) parts += Answer(AnswerKeys.STATUS_PART_MIL_ON)
        val regen = facts.regenerating()
        if (regen == true) parts += Answer(AnswerKeys.STATUS_PART_DPF_REGENERATING)
        val coolant = facts.trusted(COOLANT)
        if (coolant != null) {
            parts += Answer(
                when {
                    coolant < policy.coolantWarmBelowC -> AnswerKeys.STATUS_PART_COOLANT_WARMING_UP
                    coolant > policy.coolantHotAboveC -> AnswerKeys.STATUS_PART_COOLANT_HOT
                    else -> AnswerKeys.STATUS_PART_COOLANT_NORMAL
                },
            )
        }
        val fuel = facts.trusted(FUEL)
        if (fuel != null && fuel < policy.lowFuelPct) parts += Answer(AnswerKeys.STATUS_PART_FUEL_LOW)
        if (milOn == null && regen == null && coolant == null && fuel == null) parts += Answer(AnswerKeys.STATUS_PART_NO_READINGS)
        return Answer(AnswerKeys.STATUS_SUMMARY, mapOf("parts" to AnswerArg.Phrases(parts)))
    }
}
