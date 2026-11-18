package knightlink.voice.intents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnglishAnswerTextTest {
    private fun quantity(value: Double, unit: String) = AnswerArg.Quantity(value, unit)

    /** Plausible arguments for every placeholder a template names. */
    private fun argsFor(template: String): Map<String, AnswerArg> {
        val names = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()
        return names.associateWith { name ->
            when (name) {
                "count" -> AnswerArg.Count(2)
                "parts" -> AnswerArg.Phrases(listOf(Answer(AnswerKeys.TOPIC_FUEL), Answer(AnswerKeys.TOPIC_SPEED), Answer(AnswerKeys.TOPIC_RPM)))
                "topic", "message", "warning", "warnings", "mil" -> AnswerArg.Phrase(Answer(AnswerKeys.TOPIC_BATTERY))
                "soot" -> quantity(42.0, "percent")
                else -> quantity(42.0, "degC")
            }
        }
    }

    @Test
    fun everyKeyHasATemplateAndEveryTemplateAKey() {
        assertEquals(AnswerKeys.ALL.size, AnswerKeys.ALL.toSet().size, "duplicate keys")
        assertEquals(AnswerKeys.ALL.toSet(), EnglishAnswerText.templates.keys)
    }

    @Test
    fun everyKeyRendersWithPlausibleArgs() {
        for (key in AnswerKeys.ALL) {
            val template = EnglishAnswerText.templates.getValue(key)
            val text = EnglishAnswerText.render(Answer(key, argsFor(template)))
            assertTrue(text.isNotBlank(), key)
            assertFalse('{' in text || '}' in text, "$key left a placeholder: $text")
            assertTrue(text.first().isUpperCase(), "$key should start with a capital: $text")
        }
    }

    @Test
    fun unknownKeyThrows() {
        assertFailsWith<IllegalArgumentException> { EnglishAnswerText.render(Answer("no.such.key")) }
    }

    @Test
    fun missingArgumentThrows() {
        assertFailsWith<IllegalArgumentException> { EnglishAnswerText.render(Answer(AnswerKeys.COOLANT_NORMAL)) }
    }

    @Test
    fun quantitiesAreSpokenWithTheirUnits() {
        fun say(value: Double, unit: String) = EnglishAnswerText.render(Answer(AnswerKeys.INTAKE_VALUE, mapOf("value" to quantity(value, unit))))
        assertEquals("The intake air is at 42 degrees.", say(41.6, "degC"))
        assertEquals("The intake air is at minus 5 degrees.", say(-5.2, "degC"))
        assertEquals("The intake air is at 1 degree.", say(1.0, "degC"))
        assertEquals("The intake air is at 17 percent.", say(17.4, "percent"))
        assertEquals("The intake air is at 12.6 volts.", say(12.64, "V"))
        assertEquals("The intake air is at 12.0 volts.", say(11.99, "V"))
        assertEquals("The intake air is at 25 kilometres per hour.", say(25.4, "km/h"))
        assertEquals("The intake air is at 1970 rpm.", say(1968.0, "rpm"))
        assertEquals("The intake air is at 800 rpm.", say(803.0, "rpm"))
        assertEquals("The intake air is at 3.5 kPa.", say(3.54, "kPa"))
    }

    @Test
    fun phraseListsAreJoinedWithAnd() {
        fun list(vararg keys: String) =
            EnglishAnswerText.render(Answer(AnswerKeys.STATUS_SUMMARY, mapOf("parts" to AnswerArg.Phrases(keys.map { Answer(it) }))))
        assertEquals("I'm connected, and the fuel is low.", list(AnswerKeys.STATUS_PART_FUEL_LOW))
        assertEquals(
            "I'm connected, and the fuel is low and the engine is running hot.",
            list(AnswerKeys.STATUS_PART_FUEL_LOW, AnswerKeys.STATUS_PART_COOLANT_HOT),
        )
        assertEquals(
            "I'm connected, and the fuel is low, the engine is running hot and the DPF is regenerating.",
            list(AnswerKeys.STATUS_PART_FUEL_LOW, AnswerKeys.STATUS_PART_COOLANT_HOT, AnswerKeys.STATUS_PART_DPF_REGENERATING),
        )
    }

    @Test
    fun exactSentences() {
        assertEquals(
            "I can answer questions about the DPF, temperatures, fuel and AdBlue, battery, speed and revs, warnings, and whether you can switch off.",
            EnglishAnswerText.render(Answer(AnswerKeys.HELP)),
        )
        assertEquals("I haven't said anything yet.", EnglishAnswerText.render(Answer(AnswerKeys.REPEAT_NOTHING)))
        assertEquals(
            "The DPF is regenerating, soot at 62 percent. Keep driving if you can until it finishes.",
            EnglishAnswerText.render(Answer(AnswerKeys.DPF_REGENERATING, mapOf("soot" to quantity(62.0, "percent")))),
        )
    }
}
