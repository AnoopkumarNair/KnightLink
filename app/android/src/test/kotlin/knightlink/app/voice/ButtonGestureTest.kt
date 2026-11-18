package knightlink.app.voice

import knightlink.app.voice.ButtonGesture.Companion.KEYCODE_HEADSETHOOK
import knightlink.app.voice.ButtonGesture.Companion.KEYCODE_MEDIA_PAUSE
import knightlink.app.voice.ButtonGesture.Companion.KEYCODE_MEDIA_PLAY
import knightlink.app.voice.ButtonGesture.Companion.KEYCODE_MEDIA_PLAY_PAUSE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ButtonGestureTest {
    private var fired = 0
    private val onFired = {
        fired++
        Unit
    }

    private fun gesture(mode: MediaButtonGesture) = ButtonGesture(mode)

    private fun down(t: Long, key: Int = KEYCODE_HEADSETHOOK, repeat: Int = 0, longFlag: Boolean = false) =
        MediaKeyEvent(down = true, keyCode = key, repeatCount = repeat, eventTimeMs = t, isLongPress = longFlag)

    private fun up(t: Long, key: Int = KEYCODE_HEADSETHOOK) = MediaKeyEvent(down = false, keyCode = key, repeatCount = 0, eventTimeMs = t, isLongPress = false)

    // Long press

    @Test
    fun aLongPressFlagOnTheDownFiresAtOnceAndOnlyOnce() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        assertTrue(g.onEvent(down(0), onFired))
        assertEquals(0, fired)
        assertTrue(g.onEvent(down(500, repeat = 1, longFlag = true), onFired))
        assertEquals(1, fired)
        assertTrue(g.onEvent(down(550, repeat = 2, longFlag = true), onFired))
        assertTrue(g.onEvent(up(900), onFired))
        assertEquals(1, fired, "repeats and the release of the same press do not fire again")
    }

    @Test
    fun aRepeatCountAloneIsALongPress() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        g.onEvent(down(0), onFired)
        g.onEvent(down(400, repeat = 1), onFired)

        assertEquals(1, fired)
    }

    @Test
    fun aHeldPressFiresOnReleaseWhenNoLongPressFlagCame() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        g.onEvent(down(1000), onFired)
        g.onEvent(up(1600), onFired)

        assertEquals(1, fired, "600 ms is long enough")
    }

    @Test
    fun aShortPressDoesNothingButIsStillConsumed() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        assertTrue(g.onEvent(down(0), onFired))
        assertTrue(g.onEvent(up(599), onFired))

        assertEquals(0, fired)
    }

    @Test
    fun eachPressIsIndependent() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        g.onEvent(down(0), onFired)
        g.onEvent(down(500, repeat = 1, longFlag = true), onFired)
        g.onEvent(up(700), onFired)
        g.onEvent(down(2000), onFired)
        g.onEvent(up(2100), onFired)
        g.onEvent(down(3000), onFired)
        g.onEvent(down(3500, repeat = 1, longFlag = true), onFired)

        assertEquals(2, fired)
    }

    @Test
    fun aLostReleaseDoesNotMakeTheNextShortPressLong() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        g.onEvent(down(0), onFired) // its UP never arrives
        g.onEvent(down(5000), onFired)
        g.onEvent(up(5100), onFired)

        assertEquals(0, fired)
    }

    @Test
    fun anUpWithoutADownDoesNothing() {
        val g = gesture(MediaButtonGesture.LONG_PRESS)

        assertTrue(g.onEvent(up(9000), onFired))

        assertEquals(0, fired)
    }

    // Double press

    @Test
    fun twoReleasesWithinFourHundredMillisecondsFire() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)

        g.onEvent(down(0), onFired)
        g.onEvent(up(80), onFired)
        g.onEvent(down(250), onFired)
        assertEquals(0, fired)
        assertTrue(g.onEvent(up(399), onFired))

        assertEquals(1, fired)
    }

    @Test
    fun twoReleasesFarApartDoNotFire() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)

        g.onEvent(up(0), onFired)
        g.onEvent(up(400), onFired)

        assertEquals(0, fired, "exactly 400 ms is too slow")
    }

    @Test
    fun threeQuickPressesFireOnceAndTheThirdStartsANewPair() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)

        g.onEvent(up(0), onFired)
        g.onEvent(up(100), onFired)
        g.onEvent(up(200), onFired)
        assertEquals(1, fired)

        g.onEvent(up(300), onFired)
        assertEquals(2, fired, "the third and fourth are another pair")
    }

    @Test
    fun aSlowSecondPressBecomesTheFirstOfANewPair() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)

        g.onEvent(up(0), onFired)
        g.onEvent(up(1000), onFired)
        g.onEvent(up(1200), onFired)

        assertEquals(1, fired)
    }

    @Test
    fun aLongPressIsNotADoublePressAndDownsAreIgnored() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)

        g.onEvent(down(0), onFired)
        g.onEvent(down(500, repeat = 1, longFlag = true), onFired)
        g.onEvent(up(900), onFired)

        assertEquals(0, fired)
    }

    // Keys and modes

    @Test
    fun allMediaKeysCountAndOthersAreLeftAlone() {
        for (key in listOf(KEYCODE_HEADSETHOOK, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE)) {
            val g = gesture(MediaButtonGesture.LONG_PRESS)
            g.onEvent(down(0, key), onFired)
            assertTrue(g.onEvent(down(500, key, repeat = 1, longFlag = true), onFired), "key $key")
        }
        assertEquals(4, fired)

        val g = gesture(MediaButtonGesture.LONG_PRESS)
        assertFalse(g.onEvent(down(0, key = 87), onFired), "KEYCODE_MEDIA_NEXT")
        assertFalse(g.onEvent(down(500, key = 87, repeat = 1, longFlag = true), onFired))
        assertEquals(4, fired)
    }

    @Test
    fun offConsumesNothingAndFiresNothing() {
        val g = gesture(MediaButtonGesture.OFF)

        assertFalse(g.onEvent(down(0), onFired))
        assertFalse(g.onEvent(down(500, repeat = 1, longFlag = true), onFired))
        assertFalse(g.onEvent(up(2000), onFired))

        assertEquals(0, fired)
    }

    @Test
    fun switchingTheModeAndResettingForgetsAPressInProgress() {
        val g = gesture(MediaButtonGesture.DOUBLE_PRESS)
        g.onEvent(up(0), onFired)

        g.gesture = MediaButtonGesture.LONG_PRESS
        g.reset()
        g.gesture = MediaButtonGesture.DOUBLE_PRESS
        g.onEvent(up(100), onFired)

        assertEquals(0, fired, "the first release was forgotten")
    }

    @Test
    fun theStoredSettingParsesWithALongPressDefault() {
        assertEquals(MediaButtonGesture.LONG_PRESS, MediaButtonGesture.parse(null))
        assertEquals(MediaButtonGesture.LONG_PRESS, MediaButtonGesture.parse("nonsense"))
        for (mode in MediaButtonGesture.entries) assertEquals(mode, MediaButtonGesture.parse(mode.name))
    }
}
