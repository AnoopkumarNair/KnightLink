package knightlink.core.rules

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

/**
 * What to say when the driver asks, as phrases for the [PhraseBook]. Pure functions of [RulesState]: the voice layer may word the answer more
 * freely, but the facts come from here.
 */
public object Answers {
    /** The alert inputs: if one of these is stale, the answer "no warnings" is only partly checked. */
    private val ALERT_ROLES = setOf("regenStatus", "soot", "adblueLevel", "coolant")

    /** "Any warnings?": none, unknown, or the count followed by each warning in order of importance. Information alerts are not warnings. */
    public fun anyWarnings(state: RulesState): List<Phrase> {
        val warnings = state.alerts.filter { it.priority != AlertPriority.INFO }
        val partial = state.staleInputs.any { it in ALERT_ROLES }
        if (warnings.isEmpty()) {
            return when {
                !state.warningsCheckable -> listOf(Phrase(PhraseKeys.WARNINGS_UNKNOWN))
                partial -> listOf(Phrase(PhraseKeys.WARNINGS_NONE), Phrase(PhraseKeys.WARNINGS_PARTIAL))
                else -> listOf(Phrase(PhraseKeys.WARNINGS_NONE))
            }
        }
        val head = if (warnings.size == 1) Phrase(PhraseKeys.WARNINGS_ONE) else Phrase(PhraseKeys.WARNINGS_MANY, mapOf("count" to warnings.size.toString()))
        return listOf(head) + warnings.map { it.phrase } + if (partial) listOf(Phrase(PhraseKeys.WARNINGS_PARTIAL)) else emptyList()
    }

    /** "Can I switch off?" */
    public fun switchOff(state: RulesState): Phrase =
        when (val advice = state.switchOff) {
            SwitchOffAdvice.Safe -> Phrase(PhraseKeys.SWITCH_OFF_SAFE)
            SwitchOffAdvice.EngineOff -> Phrase(PhraseKeys.SWITCH_OFF_ENGINE_OFF)
            is SwitchOffAdvice.Unknown -> Phrase(PhraseKeys.SWITCH_OFF_UNKNOWN)
            is SwitchOffAdvice.NotYet -> when (advice.reason) {
                SwitchOffReason.REGEN_ACTIVE -> {
                    val minutes = advice.remaining?.toDouble(DurationUnit.MINUTES)?.roundToInt() ?: 0
                    // Under a minute (or no estimate) there is nothing useful to count down.
                    if (minutes >= 1) {
                        Phrase(PhraseKeys.SWITCH_OFF_REGEN_MINUTES, mapOf("minutes" to minutes.toString()))
                    } else {
                        Phrase(PhraseKeys.SWITCH_OFF_REGEN)
                    }
                }
                SwitchOffReason.HOT_EXHAUST -> {
                    val seconds = advice.remaining?.toDouble(DurationUnit.SECONDS)?.let { ceil(it).toInt() } ?: 0
                    if (seconds >= 1) Phrase(PhraseKeys.SWITCH_OFF_HOT_SECONDS, mapOf("seconds" to seconds.toString())) else Phrase(PhraseKeys.SWITCH_OFF_HOT)
                }
            }
        }

    /** "How is the DPF?" */
    public fun dpf(state: RulesState): Phrase {
        val dpf = state.dpf
        val unknown = Phrase(PhraseKeys.DPF_UNKNOWN)
        return when (dpf.phase) {
            DpfPhase.UNKNOWN -> unknown
            DpfPhase.NORMAL -> dpf.sootPct?.let { Phrase(PhraseKeys.DPF_NORMAL, mapOf("soot" to it.rounded())) } ?: unknown
            DpfPhase.SOOT_RISING -> dpf.sootPct?.let { Phrase(PhraseKeys.DPF_SOOT_RISING, mapOf("soot" to it.rounded())) } ?: unknown
            DpfPhase.REGEN_ACTIVE -> Phrase(PhraseKeys.DPF_REGEN_ACTIVE)
            DpfPhase.REGEN_COMPLETED -> dpf.last?.sootAfter?.let { Phrase(PhraseKeys.DPF_COMPLETED, mapOf("soot" to it.rounded())) } ?: unknown
            DpfPhase.REGEN_INTERRUPTED -> dpf.last?.sootAfter?.let { Phrase(PhraseKeys.DPF_INTERRUPTED, mapOf("soot" to it.rounded())) } ?: unknown
        }
    }
}

internal fun Double.rounded(): String = roundToInt().toString()
