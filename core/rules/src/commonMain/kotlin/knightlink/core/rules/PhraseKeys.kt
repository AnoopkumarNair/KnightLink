package knightlink.core.rules

/** Every phrase key the rules and answers use. A test checks that each one is in the phrase file, so a rename cannot silently silence an alert. */
internal object PhraseKeys {
    const val REGEN_ACTIVE = "dpf.regen_active"
    const val REGEN_COMPLETED = "dpf.regen_completed"
    const val REGEN_COMPLETED_AFTER_ONLY = "dpf.regen_completed_after_only"
    const val REGEN_INTERRUPTED = "dpf.regen_interrupted"
    const val REGEN_INTERRUPTED_ENGINE_OFF = "dpf.regen_interrupted_engine_off"
    const val REGEN_INTERRUPTED_ENGINE_OFF_UNKNOWN_SOOT = "dpf.regen_interrupted_engine_off_unknown_soot"
    const val SOOT_HIGH = "dpf.soot_high"
    const val ADBLUE_LOW = "def.level_low"
    const val ADBLUE_CRITICAL = "def.level_critical"
    const val COOLANT_HIGH = "engine.coolant_high"

    const val WARNINGS_NONE = "answer.warnings.none"
    const val WARNINGS_UNKNOWN = "answer.warnings.unknown"
    const val WARNINGS_ONE = "answer.warnings.one"
    const val WARNINGS_MANY = "answer.warnings.many"
    const val WARNINGS_PARTIAL = "answer.warnings.partial"
    const val SWITCH_OFF_SAFE = "answer.switch_off.safe"
    const val SWITCH_OFF_REGEN = "answer.switch_off.regen"
    const val SWITCH_OFF_REGEN_MINUTES = "answer.switch_off.regen_minutes"
    const val SWITCH_OFF_HOT_SECONDS = "answer.switch_off.hot_seconds"
    const val SWITCH_OFF_HOT = "answer.switch_off.hot"
    const val SWITCH_OFF_ENGINE_OFF = "answer.switch_off.engine_off"
    const val SWITCH_OFF_UNKNOWN = "answer.switch_off.unknown"
    const val DPF_NORMAL = "answer.dpf.normal"
    const val DPF_SOOT_RISING = "answer.dpf.soot_rising"
    const val DPF_REGEN_ACTIVE = "answer.dpf.regen_active"
    const val DPF_COMPLETED = "answer.dpf.completed"
    const val DPF_INTERRUPTED = "answer.dpf.interrupted"
    const val DPF_UNKNOWN = "answer.dpf.unknown"

    val all: List<String> = listOf(
        REGEN_ACTIVE, REGEN_COMPLETED, REGEN_COMPLETED_AFTER_ONLY, REGEN_INTERRUPTED, REGEN_INTERRUPTED_ENGINE_OFF,
        REGEN_INTERRUPTED_ENGINE_OFF_UNKNOWN_SOOT, SOOT_HIGH, ADBLUE_LOW, ADBLUE_CRITICAL, COOLANT_HIGH,
        WARNINGS_NONE, WARNINGS_UNKNOWN, WARNINGS_ONE, WARNINGS_MANY, WARNINGS_PARTIAL,
        SWITCH_OFF_SAFE, SWITCH_OFF_REGEN, SWITCH_OFF_REGEN_MINUTES, SWITCH_OFF_HOT_SECONDS, SWITCH_OFF_HOT, SWITCH_OFF_ENGINE_OFF, SWITCH_OFF_UNKNOWN,
        DPF_NORMAL, DPF_SOOT_RISING, DPF_REGEN_ACTIVE, DPF_COMPLETED, DPF_INTERRUPTED, DPF_UNKNOWN,
    )
}
