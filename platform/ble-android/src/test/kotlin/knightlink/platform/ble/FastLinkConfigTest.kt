package knightlink.platform.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FastLinkConfigTest {
    @Test
    fun theFastConnectionIntervalIsAskedForByDefaultAndCanBeSwitchedOff() {
        assertTrue(BleTransport.Config().highPriorityLink)
        assertFalse(BleTransport.Config(highPriorityLink = false).highPriorityLink)
    }
}
