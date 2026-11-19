package knightlink.core.rules

import knightlink.core.signals.Provenance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RulesDataFilesTest {
    private val config = Repo.rules()
    private val catalog = Repo.catalog(config.vehicle)
    private val book = Repo.phrases()

    @Test
    fun theHarrierRulesFileParsesAndItsIdMatchesItsPath() {
        assertEquals("rules/tata/harrier-2020-kryotec-at", config.id)
        assertEquals(Repo.HARRIER_RULES, config.id)
        assertTrue(File(Repo.root, "definitions/${config.id}.json").isFile)
    }

    @Test
    fun theVehicleItIsWrittenForExists() {
        assertTrue(File(Repo.root, "definitions/${config.vehicle}.json").isFile, config.vehicle)
    }

    private fun refs(): List<Pair<String, SignalRef>> = with(config.signals) {
        listOfNotNull(
            "regenStatus" to regenStatus,
            "soot" to soot,
            "rpm" to rpm,
            "speed" to speed,
            "coolant" to coolant,
            "adblueLevel" to adblueLevel,
        ).mapNotNull { (role, ref) -> ref?.let { role to it } } + egt.mapIndexed { index, ref -> "egt[$index]" to ref }
    }

    @Test
    fun everyConfiguredSignalExistsInTheVehiclesCatalogAndIsTrusted() {
        val trust = CatalogSignalTrust(catalog)
        for ((role, ref) in refs()) {
            val provenance = trust.provenance(ref.id, ref.ecu)
            assertNotNull(provenance, "$role: '${ref.id}' (ecu ${ref.ecu}) is not in the catalog, so the rule would never fire")
            assertTrue(
                provenance == Provenance.STANDARD || provenance == Provenance.VERIFIED,
                "$role: '${ref.id}' is $provenance, so the rule would never fire",
            )
        }
    }

    @Test
    fun everySectionOfTheHarrierFileIsPresent() {
        assertNotNull(config.dpf)
        assertNotNull(config.cooldown)
        assertNotNull(config.adblue)
        assertNotNull(config.coolant)
        assertNotNull(config.signals.regenStatus)
        assertNotNull(config.signals.soot)
        assertTrue(config.signals.egt.isNotEmpty())
        assertNotNull(config.signals.adblueLevel)
    }

    @Test
    fun theCatalogTrustReadsTheRealProvenanceAndPrefersAnEcuSpecificEntry() {
        val trust = CatalogSignalTrust(catalog)
        assertEquals(Provenance.VERIFIED, trust.provenance("def.level_pct", "rdcm"))
        assertEquals(Provenance.STANDARD, trust.provenance("dpf.soot_pct", null))
        assertEquals(Provenance.STANDARD, trust.provenance("dpf.regen_status", "ecm"))
        assertEquals(Provenance.STANDARD, trust.provenance("engine.rpm", null))
        assertEquals(Provenance.INFERRED, trust.provenance("def.reagent_level_pct", null))
        // Only defined for the reductant controller: not for "any unit", as with SignalCatalog.find.
        assertNull(trust.provenance("def.level_pct", null))
        assertNull(trust.provenance("def.level_pct", "ecm"))
        assertNull(trust.provenance("no.such_signal", null))
    }

    @Test
    fun theRealVerifiedAdblueSignalRaisesTheAlertAndTheInferredOneNever() {
        fun engineFor(adblue: SignalRef) = Rig(config.copy(signals = config.signals.copy(adblueLevel = adblue)), CatalogSignalTrust(catalog))

        val verified = engineFor(SignalRef("def.level_pct", 30_000, ecu = "rdcm"))
        verified.feed(0.seconds, "def.level_pct" to 8.0, "engine.rpm" to 800.0, ecu = "rdcm")
        assertEquals(listOf("def.level_low"), verified.eval(0.seconds).alerts.map { it.id })

        for (level in listOf(0.0, 1.0, 5.0, 50.0)) {
            val inferred = engineFor(SignalRef("def.reagent_level_pct", 30_000))
            inferred.feed(0.seconds, "def.reagent_level_pct" to level, "engine.rpm" to 800.0)
            val state = inferred.eval(0.seconds)
            assertTrue(state.alerts.isEmpty(), "inferred level $level raised an alert")
            assertTrue("adblueLevel" in state.staleInputs)
        }
    }

    // ---- phrases

    @Test
    fun everyPhraseKeyTheRulesUseIsInThePhraseFileAndNothingElseIs() {
        assertEquals(PhraseKeys.all.toSet(), book.keys)
        assertEquals(PhraseKeys.all.size, PhraseKeys.all.toSet().size)
    }

    @Test
    fun everyPhraseRendersCleanlyAndIsShortEnoughToSpeak() {
        for (key in book.keys) {
            val template = book.template(key)
            val params = PhraseBook.parametersOf(template).associateWith { "42" }
            val text = book.render(Phrase(key, params))
            assertTrue('{' !in text && '}' !in text, "$key: '$text'")
            val words = text.trim().split(Regex("\\s+"))
            assertTrue(words.size <= 20, "$key has ${words.size} words")
            assertTrue(text.last() in ".!?", "$key does not end like a sentence: '$text'")
            // Numbers are spoken, so a phrase must say what the number is a number of.
            if (params.isNotEmpty()) {
                assertTrue(
                    text.contains("percent") || text.contains("degrees") || text.contains("seconds") || text.contains("minutes") || text.contains("warnings"),
                    key,
                )
            }
        }
    }

    @Test
    fun nothingButDpfAndAdblueIsAbbreviated() {
        val abbreviation = Regex("""\b[A-Z]{2,}\b""")
        for (key in book.keys) {
            val found = abbreviation.findAll(book.template(key)).map { it.value }.filter { it != "DPF" }
            assertEquals(emptyList(), found.toList(), key)
        }
    }

    @Test
    fun theRulesFileKeepsItsNotes() {
        // A tuned value without a reason is a magic number: every section says where its values come from.
        assertTrue(Repo.text("definitions/${Repo.HARRIER_RULES}.json").contains("tune"), "the file says its numbers are initial values")
        assertNotNull(config.dpf?.notes)
        assertNotNull(config.cooldown?.notes)
        assertNotNull(config.adblue?.notes)
        assertNotNull(config.coolant?.notes)
        assertNotNull(config.alerts.notes)
    }
}
