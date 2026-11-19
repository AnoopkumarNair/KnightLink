package knightlink.voice.intents

// ---------------------------------------------------------------------------------------------------------------------
// Phrase tables. One list per intent of (phrase, weight): the phrase is matched as consecutive words of the normalised
// utterance, the weight (0..1) says how sure that phrase alone makes us. Speech recognisers get the technical words wrong, so
// the common mishearings are listed as plain synonyms. To teach the matcher a new way of asking, add a line here.
// ---------------------------------------------------------------------------------------------------------------------

private val PHRASES: Map<Intent, List<Pair<String, Double>>> = mapOf(
    Intent.STATUS to listOf(
        "status" to 0.8, "car status" to 1.0, "how is the car" to 1.0, "how is the car doing" to 1.0, "how are we doing" to 1.0,
        "everything ok" to 1.0, "everything okay" to 1.0, "is everything ok" to 1.0, "is everything okay" to 1.0, "all ok" to 0.9,
        "how is it doing" to 0.9, "how is everything" to 0.9, "how am i doing" to 0.8, "summary" to 0.7, "overview" to 0.7,
        "car report" to 0.9, "give me a report" to 0.8, "how is she doing" to 0.8,
    ),
    Intent.DPF to listOf(
        "dpf" to 1.0, "d p f" to 1.0, "deepf" to 1.0, "dp f" to 1.0, "the pf" to 0.9, "diesel particulate" to 0.95,
        "particulate filter" to 0.95, "particulate" to 0.8, "soot" to 0.9, "regen" to 0.9, "regenerating" to 0.9, "regeneration" to 0.9,
        "regenerate" to 0.9, "regenerates" to 0.9, "regenerated" to 0.8, "re gen" to 0.9, "is it regenerating" to 1.0, "burn" to 0.6,
        "burning off" to 0.8, "filter" to 0.6, "d pf" to 0.9, "dee pee f" to 0.9,
    ),
    Intent.TEMPERATURES to listOf(
        "temperatures" to 0.95, "temperature" to 0.6, "temps" to 0.8, "all temperatures" to 1.0, "all temps" to 1.0,
        "how hot is everything" to 1.0, "how hot" to 0.5, "how warm is everything" to 1.0, "heat" to 0.5,
    ),
    Intent.COOLANT_TEMPERATURE to listOf(
        "coolant" to 1.0, "coolant temperature" to 1.0, "coolant temp" to 1.0, "cool and" to 0.8, "cool and temperature" to 0.95,
        "water temp" to 0.95, "water temperature" to 0.95, "engine temperature" to 0.95, "engine temperatures" to 0.95,
        "engine temp" to 0.95, "how hot is the engine" to 1.0, "is the engine hot" to 0.95, "engine hot" to 0.85,
        "overheating" to 0.9, "is it overheating" to 1.0, "warming up" to 0.8, "warm up" to 0.7, "is the engine warm" to 0.95,
        "antifreeze" to 0.9,
    ),
    Intent.INTAKE_TEMPERATURE to listOf(
        "intake temperature" to 1.0, "intake air temperature" to 1.0, "intake temp" to 1.0, "intake air" to 0.9, "intake" to 0.7,
        "air temperature" to 0.9, "air temp" to 0.9, "inlet air" to 0.8, "inlet temperature" to 0.8, "air intake" to 0.8,
        "outside temperature" to 0.8, "ambient" to 0.6,
    ),
    Intent.EXHAUST_TEMPERATURE to listOf(
        "egt" to 1.0, "e g t" to 1.0, "e gt" to 0.9, "eg t" to 0.9, "exhaust temperature" to 0.95, "exhaust temperatures" to 0.95,
        "exhaust temp" to 0.95, "exhaust gas temperature" to 1.0, "exhaust gas temp" to 1.0, "how hot is the exhaust" to 1.0,
        "exhaust hot" to 0.8, "exhaust" to 0.6,
    ),
    Intent.FUEL_LEVEL to listOf(
        "fuel" to 0.9, "fuel level" to 1.0, "how much fuel" to 1.0, "how much diesel" to 0.95, "diesel level" to 0.95,
        "diesel left" to 0.95, "fuel left" to 1.0, "tank" to 0.8, "fuel tank" to 1.0, "refuel" to 0.8, "run out of fuel" to 0.9,
        "running out of fuel" to 0.9, "how far can i go" to 0.6,
    ),
    Intent.ADBLUE_LEVEL to listOf(
        "adblue" to 1.0, "ad blue" to 1.0, "add blue" to 1.0, "at blue" to 1.0, "a d blue" to 1.0, "ad blew" to 0.9, "add blew" to 0.9,
        "def" to 0.8, "def level" to 0.95, "urea" to 0.95, "diesel exhaust fluid" to 1.0, "exhaust fluid" to 0.95, "blue" to 0.5,
    ),
    Intent.BATTERY_VOLTAGE to listOf(
        "battery" to 1.0, "battery voltage" to 1.0, "voltage" to 0.9, "volts" to 0.9, "how many volts" to 1.0, "charging" to 0.7,
        "is it charging" to 0.9, "alternator" to 0.8, "electrical" to 0.6,
    ),
    Intent.SPEED to listOf(
        "speed" to 0.85, "how fast" to 0.95, "how fast am i going" to 1.0, "how fast are we going" to 1.0, "kilometres per hour" to 0.9,
        "kilometers per hour" to 0.9, "kph" to 0.9, "mph" to 0.9, "speedometer" to 0.9, "my speed" to 0.9, "current speed" to 0.9,
    ),
    Intent.RPM to listOf(
        "rpm" to 1.0, "r p m" to 1.0, "rp m" to 0.9, "r pm" to 0.9, "are p m" to 0.9, "revs" to 0.95, "revolutions" to 0.9,
        "engine speed" to 1.0, "rev counter" to 0.95, "tachometer" to 0.9, "how fast is the engine" to 1.0, "engine revs" to 1.0,
    ),
    Intent.WARNINGS to listOf(
        "warnings" to 1.0, "warning" to 0.9, "alerts" to 0.95, "alert" to 0.9, "any problems" to 1.0, "any problem" to 1.0,
        "anything wrong" to 1.0, "is anything wrong" to 1.0, "what is wrong" to 0.9, "check engine light" to 1.0, "check engine" to 0.9,
        "engine light" to 0.9, "fault codes" to 1.0, "fault code" to 1.0, "trouble codes" to 1.0, "error codes" to 1.0, "dtc" to 0.9,
        "dashboard light" to 0.8, "warning light" to 0.95, "any issues" to 0.9, "problems" to 0.7, "issues" to 0.6, "faults" to 0.8,
        "fault" to 0.7,
    ),
    Intent.SWITCH_OFF to listOf(
        "switch off" to 0.95, "turn off" to 0.9, "shut off" to 0.95, "shut down" to 0.9, "kill the engine" to 1.0, "stop the engine" to 1.0,
        "can i stop the engine" to 1.0, "park and switch off" to 1.0, "safe to switch off" to 1.0, "can i turn it off" to 1.0,
        "turn it off" to 0.95, "switch it off" to 0.95, "turn the engine off" to 1.0, "switch the engine off" to 1.0,
        "switch off the engine" to 1.0, "turn off the engine" to 1.0, "safe to stop" to 0.9, "safe to turn off" to 1.0,
        "engine off" to 0.85, "ignition off" to 0.8, "can i park" to 0.7, "can i switch off" to 1.0, "can i turn off" to 1.0,
        "is it safe to park" to 0.9,
    ),
    Intent.REPEAT to listOf(
        "say that again" to 1.0, "say again" to 1.0, "repeat" to 1.0, "can you repeat" to 1.0, "pardon" to 1.0, "what did you say" to 1.0,
        "come again" to 1.0, "what was that" to 0.9, "i did not hear" to 0.9, "i missed that" to 0.9, "once more" to 0.9,
        "excuse me" to 0.8, "again" to 0.6, "sorry" to 0.6,
    ),
    Intent.STOP to listOf(
        "stop" to 0.7, "stop talking" to 1.0, "quiet" to 0.95, "be quiet" to 1.0, "shut up" to 1.0, "cancel" to 0.95, "enough" to 0.9,
        "that is enough" to 1.0, "silence" to 0.9, "never mind" to 0.9, "nevermind" to 0.9, "be silent" to 1.0, "stop it" to 0.9,
    ),
)

/** Words with an apostrophe that a recogniser or keyboard gives; anything else with an apostrophe just loses it. */
private val CONTRACTIONS: Map<String, String> = mapOf(
    "what's" to "what is", "how's" to "how is", "who's" to "who is", "where's" to "where is", "when's" to "when is",
    "it's" to "it is", "that's" to "that is", "there's" to "there is", "here's" to "here is", "let's" to "let us",
    "can't" to "can not", "won't" to "will not", "i'm" to "i am", "we're" to "we are", "you're" to "you are", "i'd" to "i would",
)

/** Splits [text] into lower-case words, see [IntentMatcher] for the rules. Internal so tests can pin the normalisation down. */
internal fun normalizeWords(text: String): List<String> {
    val cleaned = text.lowercase().map { c -> if (c.isLetterOrDigit() || c == '\'' || c == '’') c else ' ' }.joinToString("")
    val expanded = cleaned.split(' ').filter { it.isNotEmpty() }.flatMap { raw ->
        val word = raw.replace('’', '\'')
        val whole = CONTRACTIONS[word]
        when {
            whole != null -> whole.split(' ')
            word.endsWith("n't") -> listOf(word.removeSuffix("n't"), "not")
            else -> listOf(word.replace("'", ""))
        }
    }.filter { it.isNotEmpty() }
    return mergeSingleLetters(expanded.flatMap(::splitLettersAndDigits))
}

/** "rpm2000" -> "rpm", "2000". */
private fun splitLettersAndDigits(word: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    for (c in word) {
        if (current.isNotEmpty() && c.isDigit() != current.last().isDigit()) {
            parts += current.toString()
            current.clear()
        }
        current.append(c)
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private fun String.isSingleLetter(): Boolean = length == 1 && this[0].isLetter()

/** "d p f" -> "dpf": a run of two or more single letters is one spelled-out word. A leading "a" or "i" of a longer run is a real word. */
private fun mergeSingleLetters(words: List<String>): List<String> {
    val result = ArrayList<String>()
    var i = 0
    while (i < words.size) {
        if (!words[i].isSingleLetter()) {
            result += words[i++]
            continue
        }
        var end = i
        while (end < words.size && words[end].isSingleLetter()) end++
        var start = i
        if (end - start >= 3 && (words[start] == "a" || words[start] == "i")) result += words[start++]
        if (end - start >= 2) {
            result += words.subList(start, end).joinToString("")
        } else if (end - start == 1) {
            result += words[start]
        }
        i = end
    }
    return result
}

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}

private class Phrase(val words: List<String>, val weight: Double)

/**
 * Understands the fixed set of push-to-talk questions in English, from the text a speech recogniser produced.
 *
 * Normalisation: lower-case, punctuation removed, contractions expanded ("what's" -> "what is"), letters and digits split apart,
 * and runs of single letters joined ("d p f" -> "dpf").
 *
 * Scoring, per intent: every table phrase that occurs in the words (consecutive, in order) contributes `weight x quality`, where
 * quality is 1.0 when every word is exact and 0.85 when words were matched fuzzily (an edit distance of 1 for table words of 5
 * or more letters, 2 for 8 or more: the usual recogniser slips like "bettery"). The intent's score is its best contribution plus
 * 15 % of the sum of its other contributions. The highest-scoring intent wins (compared before the score is capped at 1.0 for
 * reporting, so a very specific match beats a merely good one); equal scores go to the intent declared first in [Intent]. A winner below [threshold] (0.5) is [Intent.UNKNOWN] with score 0.
 *
 * Disambiguation falls out of the weights: the more specific phrase ("exhaust temperature", "engine speed", "stop the engine")
 * weighs more than the general word it contains ("temperature", "speed", "stop").
 */
public class IntentMatcher(public val threshold: Double = DEFAULT_THRESHOLD) {
    private val table: Map<Intent, List<Phrase>> = PHRASES.mapValues { (_, entries) ->
        // Normalise the table the same way as the input, and keep the strongest weight of phrases that come out identical.
        entries.map { (text, weight) -> normalizeWords(text) to weight }
            .filter { it.first.isNotEmpty() }
            .groupBy { it.first }
            .map { (words, group) -> Phrase(words, group.maxOf { it.second }) }
    }

    /** The best reading of one recognised sentence. */
    public fun match(heard: String): IntentMatch {
        val words = normalizeWords(heard)
        var best = Intent.UNKNOWN
        var bestScore = 0.0
        for (intent in Intent.entries) {
            if (intent == Intent.UNKNOWN) continue
            val score = score(words, table.getValue(intent))
            if (score > bestScore) {
                best = intent
                bestScore = score
            }
        }
        return if (bestScore >= threshold) IntentMatch(best, heard, minOf(1.0, bestScore)) else IntentMatch(Intent.UNKNOWN, heard, 0.0)
    }

    /**
     * The best reading of the recogniser's [alternatives], most confident first: the best-scoring one that is not
     * [Intent.UNKNOWN]; on equal scores the earlier alternative wins. When none is understood, UNKNOWN for the first alternative.
     */
    public fun match(alternatives: List<String>): IntentMatch {
        var best: IntentMatch? = null
        for (alternative in alternatives) {
            val candidate = match(alternative)
            if (candidate.intent != Intent.UNKNOWN && (best == null || candidate.score > best.score)) best = candidate
        }
        return best ?: IntentMatch(Intent.UNKNOWN, alternatives.firstOrNull() ?: "", 0.0)
    }

    private fun score(words: List<String>, phrases: List<Phrase>): Double {
        val contributions = phrases.mapNotNull { phrase -> quality(words, phrase.words)?.let { it * phrase.weight } }
        if (contributions.isEmpty()) return 0.0
        val best = contributions.max()
        return best + OTHERS_SHARE * (contributions.sum() - best)
    }

    /** How well [phrase] occurs in [words] (best position), or null when it does not. */
    private fun quality(words: List<String>, phrase: List<String>): Double? {
        var best: Double? = null
        for (start in 0..words.size - phrase.size) {
            var total = 0.0
            var ok = true
            for (k in phrase.indices) {
                val q = wordQuality(words[start + k], phrase[k])
                if (q == null) {
                    ok = false
                    break
                }
                total += q
            }
            if (ok) {
                val average = total / phrase.size
                if (best == null || average > best) best = average
            }
        }
        return best
    }

    private fun wordQuality(heard: String, wanted: String): Double? {
        if (heard == wanted) return 1.0
        val allowed = when {
            wanted.length >= 8 -> 2
            wanted.length >= 5 -> 1
            else -> 0
        }
        if (allowed == 0 || heard.any { it.isDigit() } || wanted.any { it.isDigit() }) return null
        return if (levenshtein(heard, wanted) <= allowed) FUZZY_QUALITY else null
    }

    public companion object {
        public const val DEFAULT_THRESHOLD: Double = 0.5
        private const val OTHERS_SHARE = 0.15
        private const val FUZZY_QUALITY = 0.85
    }
}
