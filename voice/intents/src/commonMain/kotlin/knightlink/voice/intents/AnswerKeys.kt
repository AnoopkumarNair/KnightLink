package knightlink.voice.intents

/**
 * Every answer key [Answers] produces, with the arguments its template takes. Each key has a template in [EnglishAnswerText]; a
 * test keeps the two lists equal. Argument types: Q = [AnswerArg.Quantity], N = [AnswerArg.Count], P = [AnswerArg.Phrase],
 * PS = [AnswerArg.Phrases].
 */
public object AnswerKeys {
    /** What can be asked. No args. */
    public const val HELP: String = "help"

    /** The assistant has nothing to repeat yet. No args. */
    public const val REPEAT_NOTHING: String = "repeat.nothing"

    // Link state: no args.
    public const val LINK_NOT_CONNECTED: String = "link.not_connected"
    public const val LINK_CONNECTING: String = "link.connecting"
    public const val LINK_WAITING_FOR_IGNITION: String = "link.waiting_for_ignition"
    public const val LINK_RECONNECTING: String = "link.reconnecting"

    /** No current reading of a topic. Args: `topic` (P, one of the `TOPIC_` keys). */
    public const val VALUE_UNKNOWN: String = "value.unknown"

    // Topic names for VALUE_UNKNOWN: no args.
    public const val TOPIC_COOLANT: String = "topic.coolant_temperature"
    public const val TOPIC_INTAKE: String = "topic.intake_temperature"
    public const val TOPIC_EXHAUST: String = "topic.exhaust_temperature"
    public const val TOPIC_TEMPERATURES: String = "topic.temperatures"
    public const val TOPIC_FUEL: String = "topic.fuel_level"
    public const val TOPIC_ADBLUE: String = "topic.adblue_level"
    public const val TOPIC_BATTERY: String = "topic.battery_voltage"
    public const val TOPIC_SPEED: String = "topic.speed"
    public const val TOPIC_RPM: String = "topic.rpm"
    public const val TOPIC_DPF: String = "topic.dpf"

    // Coolant: `value` (Q degC).
    public const val COOLANT_WARMING_UP: String = "coolant.warming_up"
    public const val COOLANT_NORMAL: String = "coolant.normal"
    public const val COOLANT_HOT: String = "coolant.hot"

    /** `value` (Q degC). */
    public const val INTAKE_VALUE: String = "intake.value"

    /** `value` (Q degC). */
    public const val EXHAUST_VALUE: String = "exhaust.value"

    /** All current temperatures in one sentence. Args: `parts` (PS of the `TEMPERATURE_PART_` keys). */
    public const val TEMPERATURES_LIST: String = "temperatures.list"

    // One temperature inside TEMPERATURES_LIST: `value` (Q degC).
    public const val TEMPERATURE_PART_COOLANT: String = "temperature_part.coolant"
    public const val TEMPERATURE_PART_INTAKE: String = "temperature_part.intake"
    public const val TEMPERATURE_PART_EXHAUST: String = "temperature_part.exhaust"

    /** `value` (Q percent). */
    public const val FUEL_LEVEL: String = "fuel.level"

    /** Fuel below the policy's low mark. `value` (Q percent). */
    public const val FUEL_LOW: String = "fuel.low"

    /** `value` (Q percent). */
    public const val ADBLUE_LEVEL: String = "adblue.level"

    /** `value` (Q percent). */
    public const val ADBLUE_LOW: String = "adblue.low"

    /** AdBlue from a reading that is not trusted: says so. `value` (Q percent). */
    public const val ADBLUE_UNCONFIRMED: String = "adblue.unconfirmed"

    /** As [ADBLUE_UNCONFIRMED] and the value is below the low mark. `value` (Q percent). */
    public const val ADBLUE_UNCONFIRMED_LOW: String = "adblue.unconfirmed_low"

    // Battery: `value` (Q V). BATTERY_VOLTAGE is the bare value, when the engine state is not known or the voltage is between bands.
    public const val BATTERY_VOLTAGE: String = "battery.voltage"
    public const val BATTERY_CHARGING_NORMALLY: String = "battery.charging_normally"
    public const val BATTERY_NOT_CHARGING: String = "battery.not_charging"
    public const val BATTERY_TOO_HIGH: String = "battery.too_high"
    public const val BATTERY_HEALTHY: String = "battery.healthy"
    public const val BATTERY_A_BIT_LOW: String = "battery.a_bit_low"
    public const val BATTERY_LOW: String = "battery.low"

    /** `value` (Q km/h). */
    public const val SPEED_VALUE: String = "speed.value"

    /** Speed is zero and the engine runs (or is not known to be off). No args. */
    public const val SPEED_STOPPED: String = "speed.stopped"

    /** Engine speed is zero. No args. */
    public const val ENGINE_OFF: String = "engine.off"

    /** `value` (Q rpm). */
    public const val RPM_VALUE: String = "rpm.value"

    /** Regeneration running. `soot` (Q percent). */
    public const val DPF_REGENERATING: String = "dpf.regenerating"

    /** No regeneration, soot below the due mark. `soot` (Q percent). */
    public const val DPF_IDLE: String = "dpf.idle"

    /** No regeneration, soot at or above the due mark. `soot` (Q percent). */
    public const val DPF_IDLE_DUE: String = "dpf.idle_due"

    /** Soot is known, regeneration status is not. `soot` (Q percent). */
    public const val DPF_SOOT_ONLY: String = "dpf.soot_only"

    /** Regeneration running, soot not known. No args. */
    public const val DPF_REGEN_ONLY_ACTIVE: String = "dpf.regen_only_active"

    /** No regeneration, soot not known. No args. */
    public const val DPF_REGEN_ONLY_IDLE: String = "dpf.regen_only_idle"

    /** One active warning. `message` (P). */
    public const val WARNINGS_ONE: String = "warnings.one"

    /** Several active warnings. `count` (N), `message` (P, the most important one). */
    public const val WARNINGS_MANY: String = "warnings.many"

    /** Warnings plus the engine light. `warnings` (P, WARNINGS_ONE or WARNINGS_MANY), `mil` (P, MIL_ON or MIL_ON_ONE_CODE or MIL_ON_NO_COUNT). */
    public const val WARNINGS_AND_MIL: String = "warnings.and_mil"

    /** Nothing active, light off. No args. */
    public const val WARNINGS_NONE: String = "warnings.none"

    /** Nothing active, light on. `mil` (P). */
    public const val WARNINGS_NONE_MIL_ON: String = "warnings.none_mil_on"

    /** Nothing active, light state unknown. No args. */
    public const val WARNINGS_NONE_MIL_UNKNOWN: String = "warnings.none_mil_unknown"

    /** Warning checks not running, light on. `mil` (P). */
    public const val WARNINGS_UNMONITORED_MIL_ON: String = "warnings.unmonitored_mil_on"

    /** Warning checks not running, light off. No args. */
    public const val WARNINGS_UNMONITORED_MIL_OFF: String = "warnings.unmonitored_mil_off"

    /** Warning checks not running, light state unknown. No args. */
    public const val WARNINGS_UNMONITORED_MIL_UNKNOWN: String = "warnings.unmonitored_mil_unknown"

    /** The engine light, as a part of a warnings or status answer. `count` (N, 2 or more). */
    public const val MIL_ON: String = "mil.on"

    /** `mil.on` with exactly one fault code. No args. */
    public const val MIL_ON_ONE_CODE: String = "mil.on_one_code"

    /** The engine light is on, fault count unknown. No args. */
    public const val MIL_ON_NO_COUNT: String = "mil.on_no_count"

    /** Safe to switch off, warnings are being watched. No args. */
    public const val SWITCH_OFF_YES: String = "switch_off.yes"

    /** Safe as far as the DPF goes, but nothing else is being watched. No args. */
    public const val SWITCH_OFF_YES_UNMONITORED: String = "switch_off.yes_unmonitored"

    /** An active warning says to keep running. `warning` (P). */
    public const val SWITCH_OFF_BLOCKED_BY_WARNING: String = "switch_off.blocked_by_warning"

    /** The car is moving. No args. */
    public const val SWITCH_OFF_MOVING: String = "switch_off.moving"

    /** No current trusted regeneration status. No args. */
    public const val SWITCH_OFF_UNKNOWN: String = "switch_off.unknown"

    /** Regenerating. `soot` (Q percent). */
    public const val SWITCH_OFF_REGENERATING: String = "switch_off.regenerating"

    /** Regenerating, soot not known. No args. */
    public const val SWITCH_OFF_REGENERATING_NO_SOOT: String = "switch_off.regenerating_no_soot"

    /** No regeneration, but no current trusted speed reading, so a yes is not safe. No args. */
    public const val SWITCH_OFF_SPEED_UNKNOWN: String = "switch_off.speed_unknown"

    /** The exhaust is hot. `value` (Q degC). */
    public const val SWITCH_OFF_EXHAUST_HOT: String = "switch_off.exhaust_hot"

    /** The one-sentence summary. Args: `parts` (PS of the `STATUS_PART_` keys). */
    public const val STATUS_SUMMARY: String = "status.summary"

    // Parts of STATUS_SUMMARY. STATUS_PART_WARNINGS_MANY takes `count` (N); the others take no args.
    public const val STATUS_PART_NO_WARNINGS: String = "status_part.no_warnings"
    public const val STATUS_PART_WARNING_ONE: String = "status_part.warning_one"
    public const val STATUS_PART_WARNINGS_MANY: String = "status_part.warnings_many"
    public const val STATUS_PART_MIL_ON: String = "status_part.mil_on"
    public const val STATUS_PART_UNMONITORED: String = "status_part.unmonitored"
    public const val STATUS_PART_DPF_REGENERATING: String = "status_part.dpf_regenerating"
    public const val STATUS_PART_COOLANT_WARMING_UP: String = "status_part.coolant_warming_up"
    public const val STATUS_PART_COOLANT_NORMAL: String = "status_part.coolant_normal"
    public const val STATUS_PART_COOLANT_HOT: String = "status_part.coolant_hot"
    public const val STATUS_PART_FUEL_LOW: String = "status_part.fuel_low"
    public const val STATUS_PART_NO_READINGS: String = "status_part.no_readings"

    /** Every key above, for tests and for translators. */
    public val ALL: List<String> = listOf(
        HELP, REPEAT_NOTHING,
        LINK_NOT_CONNECTED, LINK_CONNECTING, LINK_WAITING_FOR_IGNITION, LINK_RECONNECTING,
        VALUE_UNKNOWN,
        TOPIC_COOLANT, TOPIC_INTAKE, TOPIC_EXHAUST, TOPIC_TEMPERATURES, TOPIC_FUEL, TOPIC_ADBLUE, TOPIC_BATTERY, TOPIC_SPEED, TOPIC_RPM,
        TOPIC_DPF,
        COOLANT_WARMING_UP, COOLANT_NORMAL, COOLANT_HOT, INTAKE_VALUE, EXHAUST_VALUE,
        TEMPERATURES_LIST, TEMPERATURE_PART_COOLANT, TEMPERATURE_PART_INTAKE, TEMPERATURE_PART_EXHAUST,
        FUEL_LEVEL, FUEL_LOW, ADBLUE_LEVEL, ADBLUE_LOW, ADBLUE_UNCONFIRMED, ADBLUE_UNCONFIRMED_LOW,
        BATTERY_VOLTAGE, BATTERY_CHARGING_NORMALLY, BATTERY_NOT_CHARGING, BATTERY_TOO_HIGH, BATTERY_HEALTHY, BATTERY_A_BIT_LOW, BATTERY_LOW,
        SPEED_VALUE, SPEED_STOPPED, ENGINE_OFF, RPM_VALUE,
        DPF_REGENERATING, DPF_IDLE, DPF_IDLE_DUE, DPF_SOOT_ONLY, DPF_REGEN_ONLY_ACTIVE, DPF_REGEN_ONLY_IDLE,
        WARNINGS_ONE, WARNINGS_MANY, WARNINGS_AND_MIL, WARNINGS_NONE, WARNINGS_NONE_MIL_ON, WARNINGS_NONE_MIL_UNKNOWN,
        WARNINGS_UNMONITORED_MIL_ON, WARNINGS_UNMONITORED_MIL_OFF, WARNINGS_UNMONITORED_MIL_UNKNOWN,
        MIL_ON, MIL_ON_ONE_CODE, MIL_ON_NO_COUNT,
        SWITCH_OFF_YES, SWITCH_OFF_YES_UNMONITORED, SWITCH_OFF_BLOCKED_BY_WARNING, SWITCH_OFF_MOVING, SWITCH_OFF_UNKNOWN,
        SWITCH_OFF_REGENERATING, SWITCH_OFF_REGENERATING_NO_SOOT, SWITCH_OFF_EXHAUST_HOT, SWITCH_OFF_SPEED_UNKNOWN,
        STATUS_SUMMARY, STATUS_PART_NO_WARNINGS, STATUS_PART_WARNING_ONE, STATUS_PART_WARNINGS_MANY, STATUS_PART_MIL_ON,
        STATUS_PART_UNMONITORED, STATUS_PART_DPF_REGENERATING, STATUS_PART_COOLANT_WARMING_UP, STATUS_PART_COOLANT_NORMAL,
        STATUS_PART_COOLANT_HOT, STATUS_PART_FUEL_LOW, STATUS_PART_NO_READINGS,
    )
}
