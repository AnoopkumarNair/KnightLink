package knightlink.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class LexiconEngineTest {
    @Test
    fun expandsAcronymsWithWordBoundaries() {
        val engine = LexiconEngine(
            mapOf(
                "DPF" to "D P F",
                "TPMS" to "T P M S",
                "ECU" to "E C U",
            ),
        )

        assertEquals("Checking D P F status now.", engine.expand("Checking DPF status now."))
        assertEquals("T P M S pressure normal.", engine.expand("TPMS pressure normal."))
        // Ensure substring in other words isn't wrongly replaced
        assertEquals("SECURITY alert", engine.expand("SECURITY alert"))
    }

    @Test
    fun handlesSpecialCharactersAndPunctuation() {
        val engine = LexiconEngine(
            mapOf(
                "K.I.T.T." to "kit",
                "km/h" to "kilometers per hour",
            ),
        )

        assertEquals("Hello kit, speed 60 kilometers per hour", engine.expand("Hello K.I.T.T., speed 60 km/h"))
    }
}
