package knightlink.voice.intents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentMatcherTest {
    private val matcher = IntentMatcher()

    private val phrasings: Map<Intent, List<String>> = mapOf(
        Intent.STATUS to listOf("status", "how is the car", "how's the car doing", "how are we doing", "everything ok?", "is everything okay", "car status"),
        Intent.DPF to listOf(
            "how's the d p f", "is it regenerate ing", "dpf", "is it regenerating", "soot level", "diesel particulate filter status",
            "how's the deepf", "the pf", "dp f",
        ),
        Intent.TEMPERATURES to
            listOf("temperatures", "what are the temperatures", "how hot is everything", "temps", "all temperatures", "what's the temperature"),
        Intent.COOLANT_TEMPERATURE to listOf(
            "coolant temperature",
            "cool and temperature",
            "water temp",
            "engine temperature",
            "how hot is the engine",
            "is the engine hot",
            "what's the coolant",
        ),
        Intent.INTAKE_TEMPERATURE to
            listOf("intake temperature", "intake air temperature", "air temp", "what's the intake temp", "how hot is the intake air", "inlet air temperature"),
        Intent.EXHAUST_TEMPERATURE to
            listOf("exhaust temperature", "egt", "e g t", "exhaust gas temperature", "how hot is the exhaust", "what's the exhaust temp"),
        Intent.FUEL_LEVEL to listOf("fuel", "fuel level", "how much fuel", "how much diesel", "what's in the tank", "how much diesel is left"),
        Intent.ADBLUE_LEVEL to listOf("adblue", "ad blue level", "add blue level", "at blue", "diesel exhaust fluid", "how much def", "urea"),
        Intent.BATTERY_VOLTAGE to listOf("battery", "battery voltage", "bettery voltage", "how many volts", "what's the voltage", "is it charging"),
        Intent.SPEED to listOf("speed", "how fast am I going", "how fast", "what's my speed", "current speed", "speedometer"),
        Intent.RPM to listOf("rpm", "what's the r p m", "revs", "engine speed", "rev counter", "how fast is the engine going", "engine revs"),
        Intent.WARNINGS to
            listOf(
                "warnings",
                "any warnings",
                "alerts",
                "any problems",
                "is anything wrong",
                "check engine light",
                "fault codes",
                "anything wrong with the car",
            ),
        Intent.SWITCH_OFF to listOf(
            "can I switch off",
            "can i turn it off",
            "stop the engine",
            "can I stop the engine?",
            "safe to switch off",
            "kill the engine",
            "park and switch off",
            "shut down",
        ),
        Intent.REPEAT to listOf("say that again", "repeat", "pardon", "what did you say", "come again", "can you repeat that"),
        Intent.STOP to listOf("stop", "stop talking", "quiet", "shut up", "cancel", "enough"),
    )

    @Test
    fun everyIntentUnderstoodForManyPhrasings() {
        for ((intent, heard) in phrasings) {
            assertTrue(heard.size >= 6, "$intent needs at least 6 phrasings")
            for (phrase in heard) {
                val match = matcher.match(phrase)
                assertEquals(intent, match.intent, "'$phrase' should be $intent but was ${match.intent} (${match.score})")
                assertEquals(phrase, match.heard)
                assertTrue(match.score in 0.5..1.0, "'$phrase' score ${match.score}")
            }
        }
    }

    @Test
    fun speechErrorsAreTolerated() {
        assertEquals(Intent.DPF, matcher.match("how's the d p f").intent)
        assertEquals(Intent.DPF, matcher.match("is it regenerate ing").intent)
        assertEquals(Intent.ADBLUE_LEVEL, matcher.match("add blue level").intent)
        assertEquals(Intent.BATTERY_VOLTAGE, matcher.match("bettery voltage").intent)
        assertEquals(Intent.COOLANT_TEMPERATURE, matcher.match("cool and temperature").intent)
        assertEquals(Intent.RPM, matcher.match("what's the r p m").intent)
        // One letter off in a longer word is forgiven by the fuzzy match, with a lower score than the exact word.
        val fuzzy = matcher.match("batery")
        assertEquals(Intent.BATTERY_VOLTAGE, fuzzy.intent)
        assertTrue(fuzzy.score < matcher.match("battery").score)
    }

    @Test
    fun confusablesGoToTheMoreSpecificIntent() {
        assertEquals(Intent.EXHAUST_TEMPERATURE, matcher.match("exhaust temperature").intent)
        assertEquals(Intent.EXHAUST_TEMPERATURE, matcher.match("what are the exhaust temperatures").intent)
        assertEquals(Intent.TEMPERATURES, matcher.match("temperatures").intent)
        assertEquals(Intent.TEMPERATURES, matcher.match("how hot is everything").intent)
        assertEquals(Intent.COOLANT_TEMPERATURE, matcher.match("how hot is the engine").intent)
        assertEquals(Intent.SWITCH_OFF, matcher.match("stop the engine?").intent)
        assertEquals(Intent.STOP, matcher.match("stop").intent)
        assertEquals(Intent.STOP, matcher.match("stop talking").intent)
        assertEquals(Intent.RPM, matcher.match("engine speed").intent)
        assertEquals(Intent.SPEED, matcher.match("speed").intent)
        assertEquals(Intent.FUEL_LEVEL, matcher.match("how much diesel").intent)
        assertEquals(Intent.ADBLUE_LEVEL, matcher.match("diesel exhaust fluid").intent)
        assertEquals(Intent.ADBLUE_LEVEL, matcher.match("adblue tank").intent)
        assertEquals(Intent.DPF, matcher.match("is it regenerating").intent)
        assertEquals(Intent.DPF, matcher.match("dpf status").intent)
        assertEquals(Intent.STATUS, matcher.match("status").intent)
    }

    @Test
    fun alternativesTakeTheBestScoringUnderstoodOne() {
        assertEquals(Intent.DPF, matcher.match(listOf("play some music", "how's the dpf")).intent)
        // A weaker earlier alternative loses to a stronger later one.
        val match = matcher.match(listOf("fuel", "battery voltage"))
        assertEquals(Intent.BATTERY_VOLTAGE, match.intent)
        assertEquals("battery voltage", match.heard)
    }

    @Test
    fun alternativesTieGoesToTheEarlierOne() {
        assertEquals(Intent.BATTERY_VOLTAGE, matcher.match(listOf("battery voltage", "fuel level")).intent)
        assertEquals(Intent.FUEL_LEVEL, matcher.match(listOf("fuel level", "battery voltage")).intent)
    }

    @Test
    fun unknownPhrasesAreUnknownWithScoreZero() {
        for (phrase in listOf("play some music", "", "   ", "what's the weather", "asdf qwer", "...", "call mum")) {
            val match = matcher.match(phrase)
            assertEquals(Intent.UNKNOWN, match.intent, phrase)
            assertEquals(0.0, match.score, phrase)
            assertEquals(phrase, match.heard)
        }
        val none = matcher.match(listOf("play some music", "what's the weather"))
        assertEquals(Intent.UNKNOWN, none.intent)
        assertEquals("play some music", none.heard)
        assertEquals(Intent.UNKNOWN, matcher.match(emptyList()).intent)
    }

    @Test
    fun unknownAnswersWithTheHelpKey() {
        val intent = matcher.match("what's the weather").intent
        assertEquals(AnswerKeys.HELP, answerOf(intent, context()).key)
    }

    @Test
    fun normalisationRules() {
        assertEquals(listOf("what", "is", "the", "dpf"), normalizeWords("What's the D.P.F.?"))
        assertEquals(listOf("is", "it", "a", "dpf"), normalizeWords("is it a d p f"))
        assertEquals(listOf("can", "i", "turn", "it", "off"), normalizeWords("Can I turn it off?"))
        assertEquals(listOf("rpm", "2000"), normalizeWords("rpm2000"))
        assertEquals(listOf("i", "do", "not", "know"), normalizeWords("I don’t know"))
        assertEquals(emptyList(), normalizeWords("  ?! "))
    }

    @Test
    fun aHigherThresholdRejectsFuzzyMatches() {
        assertEquals(Intent.UNKNOWN, IntentMatcher(threshold = 0.95).match("batery").intent)
        assertEquals(Intent.BATTERY_VOLTAGE, IntentMatcher(threshold = 0.95).match("battery").intent)
    }
}
