package knightlink.voice.intents

import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalValue
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val ALL_STANDARD = SignalProvenance { _, _ -> Provenance.STANDARD }

/** Provenance [overrides] per signal id, STANDARD for the rest. */
internal fun provenanceOf(overrides: Map<String, Provenance>) = SignalProvenance { id, _ -> overrides[id] ?: Provenance.STANDARD }

internal fun reading(id: String, value: Double, unit: String = "x", inRange: Boolean = true, ecu: String? = null) =
    Reading(ecu, SignalValue(id, value, unit, inRange))

/** A context at t=100 s whose readings were taken at t=99 s, so they are 1 s old; the default max age is 10 s. */
internal fun context(
    vararg readings: Reading,
    at: Duration = 99.seconds,
    now: Duration = 100.seconds,
    link: LinkStatus = LinkStatus.LIVE,
    provenance: SignalProvenance = ALL_STANDARD,
    maxAge: (String) -> Duration = ReadingAges.DEFAULT,
) = AnswerContext(TelemetrySnapshot.EMPTY.with(readings.toList(), at), now, link, provenance, maxAge)

internal class FakeWarnings(
    override val isMonitoring: Boolean = true,
    private val active: List<ActiveWarning> = emptyList(),
) : WarningsSource {
    override fun activeWarnings(): List<ActiveWarning> = active
}

internal fun warning(id: String, blocksSwitchOff: Boolean = false, key: String = AnswerKeys.TOPIC_COOLANT) =
    ActiveWarning(id, ActiveWarning.Severity.CAUTION, Answer(key), blocksSwitchOff)

internal fun answerOf(intent: Intent, context: AnswerContext, warnings: WarningsSource = FakeWarnings(), policy: AnswerPolicy = AnswerPolicy()) =
    Answers.answer(intent, context, warnings, policy)

internal fun textOf(answer: Answer): String = EnglishAnswerText.render(answer)
