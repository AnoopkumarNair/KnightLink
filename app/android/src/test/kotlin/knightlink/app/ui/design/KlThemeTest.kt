package knightlink.app.ui.design

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlThemeTest {
    private val surfaces = listOf(KlColors.Background, KlColors.Surface, KlColors.SurfaceRaised)

    @Test
    fun theContrastRatioMatchesTheDefinition() {
        assertEquals(21.0, KlContrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, KlContrast.ratio(Color.White, Color.White), 0.0001)
    }

    @Test
    fun everyTextColourReadsAtFourPointFiveToOneOnEverySurface() {
        val texts = mapOf(
            "OnSurface" to KlColors.OnSurface,
            "OnSurfaceMuted" to KlColors.OnSurfaceMuted,
            "Accent" to KlColors.Accent,
            "Good" to KlColors.Good,
            "Attention" to KlColors.Attention,
            "Problem" to KlColors.Problem,
        )
        for ((name, color) in texts) {
            for (surface in surfaces) {
                val ratio = KlContrast.ratio(color, surface)
                assertTrue(ratio >= 4.5, "$name on $surface is only $ratio")
            }
        }
    }

    @Test
    fun theButtonTextReadsOnTheAccent() {
        assertTrue(KlContrast.ratio(KlColors.OnAccent, KlColors.Accent) >= 7.0)
    }

    @Test
    fun touchTargetsAreAtLeastFortyEightDp() {
        assertTrue(KlSpacing.Touch.value >= 48f)
    }
}
