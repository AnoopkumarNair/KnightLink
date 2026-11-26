package knightlink.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class AiBrainTest {
    @Test
    fun factGuardAllowsVerifiedNumbersAndRejectsHallucinations() {
        val verified = listOf(88.0, 780.0)
        val ok = TelemetryFactGuard.verifyOrFallback(
            candidateText = "Coolant is 88 degrees at 780 rpm.",
            verifiedValues = verified,
            deterministicFallback = "Coolant is 88 °C.",
        )
        assertEquals("Coolant is 88 degrees at 780 rpm.", ok)

        val hallucinated = TelemetryFactGuard.verifyOrFallback(
            candidateText = "Coolant is 115 degrees!",
            verifiedValues = verified,
            deterministicFallback = "Coolant is 88 °C.",
        )
        assertEquals("Coolant is 88 °C.", hallucinated)
    }
}
