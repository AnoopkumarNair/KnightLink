package knightlink.core.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdapterDefinitionTest {
    private val minimal = """{"schemaVersion":1,"id":"adapters/x","title":"X","chipset":"elm327","initScript":["ATZ","ATE0","ATH1"]}"""

    @Test
    fun everyNewFieldIsOptionalAndDefaultsToTheDriversOwnDefaults() {
        val definition = AdapterDefinition.parse(minimal)
        assertEquals(emptyList(), definition.probeScript)
        assertEquals(emptyList(), definition.pollScript)
        assertEquals(AdapterDefinition.DEFAULT_ESSENTIAL_COMMANDS, definition.essentialCommands)
        assertEquals(Elm327Driver.Config(), definition.driverConfig())
    }

    @Test
    fun scriptsTimingAndEssentialCommandsAreReadFromTheFile() {
        val definition = AdapterDefinition.parse(
            """{"schemaVersion":1,"id":"adapters/x","title":"X","chipset":"elm327","initScript":["ATZ"],
                "probeScript":["ATST64"],"pollScript":["ATST32"],"essentialCommands":["ATE0"],
                "timing":{"warmUp":false,"warmUpDelayMs":100,"postResetDelayMs":800,"stoppedRetryDelayMs":50,
                          "commandTimeoutMs":4500,"resetTimeoutMs":9000,"searchingTimeoutMs":12000}}""",
        )
        assertEquals(listOf("ATST64"), definition.probeScript)
        assertEquals(listOf("ATST32"), definition.pollScript)
        assertEquals(
            Elm327Driver.Config(
                commandTimeout = 4500.milliseconds,
                resetTimeout = 9.seconds,
                searchingTimeout = 12.seconds,
                warmUp = false,
                warmUpDelay = 100.milliseconds,
                postResetDelay = 800.milliseconds,
                stoppedRetryDelay = 50.milliseconds,
                essentialCommands = listOf("ATE0"),
            ),
            definition.driverConfig(),
        )
    }

    @Test
    fun anUnknownTimingKeyIsRejectedSoATypoDoesNotPassUnnoticed() {
        assertFailsWith<Exception> {
            AdapterDefinition.parse(
                """{"schemaVersion":1,"id":"a","title":"X","chipset":"elm327","initScript":["ATZ"],"timing":{"commandTimeoutSeconds":3}}""",
            )
        }
    }

    @Test
    fun aNonPositiveTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AdapterDefinition.parse(
                """{"schemaVersion":1,"id":"a","title":"X","chipset":"elm327","initScript":["ATZ"],"timing":{"commandTimeoutMs":0}}""",
            ).driverConfig()
        }
    }
}
