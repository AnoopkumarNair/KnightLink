package knightlink.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigAndPhraseTest {
    private val valid = """
        {
          "schemaVersion": 1, "id": "rules/x", "title": "x", "vehicle": "vehicles/x",
          "signals": { "rpm": { "id": "engine.rpm", "maxAgeMs": 3000 }, "speed": { "id": "vehicle.speed", "maxAgeMs": 3000 } },
          "engine": { "runningRpm": 300 },
          "alerts": { "reannounceMs": { "critical": 1, "warning": 2, "advisory": 0, "info": 0 }, "quietAfterClearMs": 1, "minGapMs": 1, "eventLifetimeMs": 1 }
        }
    """.trimIndent()

    private fun parse(text: String) = RulesConfig.parse(text)

    private fun rejects(text: String, containing: String? = null) {
        val e = assertFailsWith<RulesConfigException> { parse(text) }
        if (containing != null) assertTrue(containing in e.message.orEmpty(), e.message)
    }

    @Test
    fun aMinimalFileParsesAndEverySectionIsOptional() {
        val config = parse(valid)
        assertEquals("rules/x", config.id)
        assertEquals(null, config.dpf)
        assertEquals(null, config.adblue)
        assertEquals(emptyList(), config.signals.egt)
    }

    @Test
    fun unknownKeysAreRejected() {
        rejects(valid.replace("\"title\": \"x\"", "\"title\": \"x\", \"surprise\": 1"))
        rejects(valid.replace("\"maxAgeMs\": 3000 }, \"speed\"", "\"maxAgeMs\": 3000, \"oops\": 1 }, \"speed\""))
    }

    @Test
    fun aMissingRequiredPartIsRejected() {
        rejects(valid.replace(""""engine": { "runningRpm": 300 },""", ""))
        rejects(valid.replace(""""speed": { "id": "vehicle.speed", "maxAgeMs": 3000 }""", """"coolant": { "id": "c", "maxAgeMs": 1 }"""))
        rejects("not json")
    }

    @Test
    fun aNewerSchemaIsRejected() {
        rejects(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), "schema")
        rejects(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0"), "schema")
    }

    @Test
    fun emptyIdsAndSillyAgesAreRejected() {
        rejects(valid.replace("\"engine.rpm\"", "\"\""), "id is empty")
        rejects(valid.replace("\"id\": \"rules/x\"", "\"id\": \" \""), "id is empty")
        rejects(valid.replace("\"vehicle\": \"vehicles/x\"", "\"vehicle\": \"\""), "vehicle")
        rejects(valid.replace("\"maxAgeMs\": 3000 }, \"speed\"", "\"maxAgeMs\": 0 }, \"speed\""), "maxAgeMs")
        rejects(valid.replace("\"maxAgeMs\": 3000 }, \"speed\"", "\"maxAgeMs\": 99999999 }, \"speed\""), "maxAgeMs")
        rejects(valid.replace("\"runningRpm\": 300", "\"runningRpm\": 0"), "runningRpm")
    }

    @Test
    fun valuesThatMakeNoSenseAreRejected() {
        val ok = testConfig()
        ok.validate()
        fun bad(config: RulesConfig, containing: String) {
            val e = assertFailsWith<RulesConfigException> { config.validate() }
            assertTrue(containing in e.message.orEmpty(), e.message)
        }
        val dpf = ok.dpf!!
        bad(ok.copy(dpf = dpf.copy(sootRisingClearPct = 80.0)), "sootRisingClearPct")
        bad(ok.copy(dpf = dpf.copy(sootRisingPct = 120.0)), "sootRisingClearPct")
        bad(ok.copy(dpf = dpf.copy(sootHighPct = 70.0)), "sootHighPct")
        bad(ok.copy(dpf = dpf.copy(completedSootPct = -1.0)), "completedSootPct")
        bad(ok.copy(dpf = dpf.copy(regenEndConfirmReadings = 0)), "regenEndConfirmReadings")
        bad(ok.copy(dpf = dpf.copy(regenActiveMask = 0)), "regenActiveMask")
        bad(ok.copy(dpf = dpf.copy(resultHoldMs = -1)), "HoldMs")
        bad(ok.copy(adblue = ok.adblue!!.copy(criticalPct = 10.0)), "criticalPct")
        bad(ok.copy(adblue = ok.adblue!!.copy(clearMarginPct = -1.0)), "clearMarginPct")
        bad(ok.copy(coolant = ok.coolant!!.copy(highC = 10.0)), "highC")
        bad(ok.copy(coolant = ok.coolant!!.copy(clearMarginC = 200.0)), "clearMarginC")
        bad(ok.copy(cooldown = ok.cooldown!!.copy(cooldownIdleMs = -1)), "cooldownIdleMs")
        bad(ok.copy(alerts = ok.alerts.copy(minGapMs = -1)), "timings")
        bad(ok.copy(alerts = testAlertsConfig(critical = -1)), "reannounceMs")
        bad(ok.copy(signals = ok.signals.copy(egt = listOf(SignalRef("", 1)))), "egt[0]")
        bad(ok.copy(signals = ok.signals.copy(adblueLevel = SignalRef("a", 1, ecu = " "))), "ecu")
    }

    @Test
    fun aPhraseBookRendersParameters() {
        val book = PhraseBook.parse("""{"schemaVersion":1,"language":"en","phrases":{"a":"Hello {name}, {n} times.","b":"Plain."}}""")
        assertEquals("en", book.language)
        assertEquals(setOf("a", "b"), book.keys)
        assertEquals("Hello Bob, 3 times.", book.render(Phrase("a", mapOf("name" to "Bob", "n" to "3"))))
        assertEquals("Plain.", book.render(Phrase("b", mapOf("unused" to "1"))))
        assertEquals(setOf("name", "n"), PhraseBook.parametersOf(book.template("a")))
    }

    @Test
    fun aPhraseBookThrowsOnAnUnknownKeyOrMissingParameter() {
        val book = PhraseBook.parse("""{"schemaVersion":1,"language":"en","phrases":{"a":"Hello {name}."}}""")
        assertFailsWith<PhraseException> { book.render(Phrase("nope")) }
        assertFailsWith<PhraseException> { book.render(Phrase("a")) }
        assertFailsWith<PhraseException> { book.render(Phrase("a", mapOf("other" to "x"))) }
    }

    @Test
    fun aMalformedPhraseFileIsRejected() {
        assertFailsWith<PhraseException> { PhraseBook.parse("nope") }
        assertFailsWith<PhraseException> { PhraseBook.parse("""{"schemaVersion":2,"language":"en","phrases":{}}""") }
        assertFailsWith<PhraseException> { PhraseBook.parse("""{"schemaVersion":1,"language":"","phrases":{}}""") }
        assertFailsWith<PhraseException> { PhraseBook.parse("""{"schemaVersion":1,"language":"en","phrases":{"a":" "}}""") }
        assertFailsWith<PhraseException> { PhraseBook.parse("""{"schemaVersion":1,"language":"en","phrases":{},"extra":1}""") }
    }
}
