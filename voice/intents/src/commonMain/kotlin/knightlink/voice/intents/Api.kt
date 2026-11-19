package knightlink.voice.intents

import knightlink.core.signals.Provenance
import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.time.Duration

/**
 * The fixed set of questions push-to-talk understands (M3). M6 widens it; M7 adds free phrasing on top, still answered from the
 * same deterministic facts.
 */
public enum class Intent {
    /** "How's the car?", "status", "everything ok?" */
    STATUS,

    /** "How's the DPF?", "soot level?", "is it regenerating?" */
    DPF,

    /** "Temperatures?", "how hot is the engine?" without naming one sensor: coolant, intake and exhaust together. */
    TEMPERATURES,
    COOLANT_TEMPERATURE,
    INTAKE_TEMPERATURE,
    EXHAUST_TEMPERATURE,

    FUEL_LEVEL,
    ADBLUE_LEVEL,
    BATTERY_VOLTAGE,
    SPEED,
    RPM,

    /** "Any warnings?", "is anything wrong?" */
    WARNINGS,

    /** "Can I switch off now?", "is it safe to turn the engine off?" */
    SWITCH_OFF,

    /** "Say that again", "repeat", "pardon?" */
    REPEAT,

    /** "Stop", "quiet", "cancel": stops speaking, says nothing. */
    STOP,

    /** Nothing above matched: the answer lists what can be asked. */
    UNKNOWN,
}

/**
 * What [IntentMatcher] understood.
 *
 * @property heard the recognised text the intent came from (one of the recogniser's alternatives), as given
 * @property score how sure the match is, 0 (nothing) to 1 (an exact phrase); [Intent.UNKNOWN] has 0
 */
public data class IntentMatch(val intent: Intent, val heard: String, val score: Double)

/**
 * One spoken sentence as a localisable key plus its parameters. Never free text: a renderer ([AnswerText]) turns it into words for
 * one language, so every number in it comes from [args], which come from the telemetry snapshot.
 *
 * @property key stable id of the sentence template, e.g. `dpf.regenerating` (the full list is [AnswerKeys])
 * @property args named parameters of the template
 */
public data class Answer(val key: String, val args: Map<String, AnswerArg> = emptyMap())

/** A parameter of an [Answer]. */
public sealed interface AnswerArg {
    /** A measured value; [unit] is the definitions' unit string (`degC`, `percent`, `V`, `km/h`, `rpm`, ...). */
    public data class Quantity(val value: Double, val unit: String) : AnswerArg

    /** A count, e.g. the number of fault codes. */
    public data class Count(val value: Int) : AnswerArg

    /** A nested sentence or phrase, e.g. the first active warning's own text, or one part of a status summary. */
    public data class Phrase(val answer: Answer) : AnswerArg

    /** Several phrases spoken as a list ("a, b and c"). */
    public data class Phrases(val answers: List<Answer>) : AnswerArg
}

/** Turns an [Answer] into words in one language. [EnglishAnswerText] is the built-in one. */
public fun interface AnswerText {
    public fun render(answer: Answer): String
}

/** Where the link to the car is, as far as answers care. The app maps its session state onto this. */
public enum class LinkStatus {
    /** Live data is being polled: readings can be fresh. */
    LIVE,

    /** Connecting, initialising or probing. */
    CONNECTING,

    /** The adapter is up but the car does not answer (ignition off). */
    WAITING_FOR_IGNITION,

    /** The link dropped or the adapter is being re-initialised; readings are from before. */
    RECONNECTING,

    /** No session, or it failed or was stopped. */
    NOT_CONNECTED,
}

/**
 * Everything an answer may use, captured at the moment the question is answered.
 *
 * @property snapshot the latest readings; [knightlink.core.telemetry.TimedReading.at] is time since the session started
 * @property now the current time in the same frame as the readings' `at` (time since the session started)
 * @property link where the connection is; anything but [LinkStatus.LIVE] makes every reading count as stale
 * @property provenance how far each signal can be trusted: safety answers use only [Provenance.STANDARD] and
 *   [Provenance.VERIFIED] readings; an unknown signal (null) counts as untrusted
 * @property maxAge how old a reading of a signal may be and still count as current (see [ReadingAges])
 */
public data class AnswerContext(
    val snapshot: TelemetrySnapshot,
    val now: Duration,
    val link: LinkStatus,
    val provenance: SignalProvenance,
    val maxAge: (signalId: String) -> Duration = ReadingAges.DEFAULT,
)

/** How far a signal's readings can be trusted, from the definitions that produced them. */
public fun interface SignalProvenance {
    /**
     * @param ecu the reading's ECU id (null when the definitions did not tie it to one)
     * @return the provenance of the definition entry that decodes [signalId] for [ecu]; null when unknown
     */
    public fun of(signalId: String, ecu: String?): Provenance?
}

/**
 * Seam for the rules engine (`core/rules`, built elsewhere): the warnings that are active now. Integration maps the alert engine
 * onto this; until then [NoWarningsSource] stands in.
 */
public interface WarningsSource {
    /**
     * False while nothing is watching for warnings (no rules engine yet). Answers then say so instead of claiming "no warnings":
     * an empty list from a source that does not watch means nothing.
     */
    public val isMonitoring: Boolean

    /** Active warnings, most important first. Read on the caller's thread; must be cheap and must not block. */
    public fun activeWarnings(): List<ActiveWarning>
}

/**
 * One active warning.
 *
 * @property id stable id, e.g. `dpf.regen_interrupted` (for de-duplication and logs)
 * @property severity how serious
 * @property message what to say about it: one sentence, as a key and parameters like any other answer
 * @property blocksSwitchOff true when the engine should keep running for now (e.g. an active regeneration, a hot turbo)
 */
public data class ActiveWarning(
    val id: String,
    val severity: Severity,
    val message: Answer,
    val blocksSwitchOff: Boolean = false,
) {
    public enum class Severity { INFO, CAUTION, CRITICAL }
}

/** The default [WarningsSource]: nothing watches for warnings yet, so it knows of none and says it does not monitor. */
public object NoWarningsSource : WarningsSource {
    override val isMonitoring: Boolean get() = false

    override fun activeWarnings(): List<ActiveWarning> = emptyList()
}

/**
 * Seam for voice output (`voice/api` Speaker, built elsewhere): says an answer. Integration maps the Speaker onto this; until then
 * [PrintAnswerOutput] (and, in the app, an on-screen plus plain-TTS stub) stands in.
 */
public interface AnswerOutput {
    /**
     * Says [answer]; [text] is its rendering in the current language, for outputs that just speak or show text. Returns at once;
     * a new call interrupts whatever is still being said.
     */
    public fun speak(answer: Answer, text: String)

    /** Stops speaking now. Does nothing when silent. */
    public fun stop()
}

/** The default [AnswerOutput]: writes each answer's text to [sink] (standard output unless given). */
public class PrintAnswerOutput(private val sink: (String) -> Unit = ::println) : AnswerOutput {
    override fun speak(answer: Answer, text: String) {
        sink(text)
    }

    override fun stop() {}
}
