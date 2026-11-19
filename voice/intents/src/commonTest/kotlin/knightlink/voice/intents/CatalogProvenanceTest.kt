package knightlink.voice.intents

import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.EcuDefinition
import knightlink.core.signals.ParameterDefinition
import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalCatalog
import knightlink.core.signals.SignalDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CatalogProvenanceTest {
    private fun parameter(pid: String, signalId: String, provenance: Provenance, ecu: String? = null) =
        ParameterDefinition("01", pid, ecu, provenance, listOf(SignalDefinition(signalId, "A", "x")))

    private fun catalog(vararg parameters: ParameterDefinition): SignalCatalog = SignalCatalog.of(
        listOf(
            DefinitionFile(
                schemaVersion = 1,
                id = "test",
                kind = DefinitionFile.Kind.STANDARD,
                title = "test",
                ecus = listOf(EcuDefinition("engine", "Engine", "7E0", "7E8"), EcuDefinition("dcm", "Other", "7E1", "7E9")),
                parameters = parameters.toList(),
            ),
        ),
    )

    @Test
    fun genericEntryAppliesToEveryEcuAndToUnknownEcu() {
        val provenance = catalog(parameter("05", "a.sig", Provenance.STANDARD)).signalProvenance()
        assertEquals(Provenance.STANDARD, provenance.of("a.sig", null))
        assertEquals(Provenance.STANDARD, provenance.of("a.sig", "engine"))
        assertEquals(Provenance.STANDARD, provenance.of("a.sig", "somewhere-else"))
        assertNull(provenance.of("other.sig", "engine"))
    }

    @Test
    fun ecuEntryBeatsTheGenericOneForThatEcu() {
        val provenance = catalog(
            parameter("05", "a.sig", Provenance.STANDARD),
            parameter("06", "a.sig", Provenance.INFERRED, ecu = "engine"),
        ).signalProvenance()
        assertEquals(Provenance.INFERRED, provenance.of("a.sig", "engine"))
        assertEquals(Provenance.STANDARD, provenance.of("a.sig", "dcm"))
        assertEquals(Provenance.STANDARD, provenance.of("a.sig", null))
    }

    @Test
    fun ecuOnlyEntryIsUnknownForOtherEcus() {
        val provenance = catalog(parameter("06", "a.sig", Provenance.VERIFIED, ecu = "engine")).signalProvenance()
        assertEquals(Provenance.VERIFIED, provenance.of("a.sig", "engine"))
        assertNull(provenance.of("a.sig", "dcm"))
        assertNull(provenance.of("a.sig", null))
    }

    @Test
    fun sameSignalDefinedTwiceKeepsTheLeastTrusted() {
        fun resolved(a: Provenance, b: Provenance) =
            catalog(parameter("05", "a.sig", a), parameter("06", "a.sig", b)).signalProvenance().of("a.sig", null)
        assertEquals(Provenance.COMMUNITY, resolved(Provenance.STANDARD, Provenance.COMMUNITY))
        assertEquals(Provenance.COMMUNITY, resolved(Provenance.COMMUNITY, Provenance.STANDARD))
        assertEquals(Provenance.INFERRED, resolved(Provenance.VERIFIED, Provenance.INFERRED))
        assertEquals(Provenance.INFERRED, resolved(Provenance.INFERRED, Provenance.COMMUNITY))
        assertTrue(resolved(Provenance.STANDARD, Provenance.VERIFIED).isTrusted())
        // The same rule holds for entries of one ECU.
        val perEcu = catalog(
            parameter("05", "a.sig", Provenance.STANDARD, ecu = "engine"),
            parameter("06", "a.sig", Provenance.INFERRED, ecu = "engine"),
        ).signalProvenance()
        assertEquals(Provenance.INFERRED, perEcu.of("a.sig", "engine"))
    }

    @Test
    fun everySignalOfAMultiSignalParameterIsCovered() {
        val multi = ParameterDefinition(
            "01",
            "68",
            null,
            Provenance.STANDARD,
            listOf(SignalDefinition("s.one", "A", "x"), SignalDefinition("s.two", "B", "x")),
        )
        val provenance = catalog(multi).signalProvenance()
        assertEquals(Provenance.STANDARD, provenance.of("s.one", null))
        assertEquals(Provenance.STANDARD, provenance.of("s.two", null))
    }

    @Test
    fun trustedMeansStandardOrVerified() {
        assertTrue(Provenance.STANDARD.isTrusted())
        assertTrue(Provenance.VERIFIED.isTrusted())
        assertFalse(Provenance.COMMUNITY.isTrusted())
        assertFalse(Provenance.INFERRED.isTrusted())
        assertFalse((null as Provenance?).isTrusted())
    }

    @Test
    fun readingAgesDefaultIsTenSeconds() {
        assertEquals(10.seconds, ReadingAges.DEFAULT("anything"))
    }

    @Test
    fun readingAgesFromPeriods() {
        val ages = ReadingAges.fromPeriods(
            mapOf("fast" to 150.milliseconds, "normal" to 1.seconds, "slow" to 6.seconds, "once" to Duration.INFINITE, "zero" to Duration.ZERO),
        )
        assertEquals(5.seconds, ages("fast"))
        assertEquals(5.seconds, ages("normal"))
        assertEquals(18.seconds, ages("slow"))
        assertEquals(Duration.INFINITE, ages("once"))
        assertEquals(5.seconds, ages("zero"))
        assertEquals(5.seconds, ages("not.polled"))
        val custom = ReadingAges.fromPeriods(mapOf("slow" to 6.seconds, "fast" to 150.milliseconds), minimum = 2.seconds, factor = 4)
        assertEquals(24.seconds, custom("slow"))
        assertEquals(2.seconds, custom("fast"))
        assertEquals(2.seconds, custom("missing"))
    }
}
