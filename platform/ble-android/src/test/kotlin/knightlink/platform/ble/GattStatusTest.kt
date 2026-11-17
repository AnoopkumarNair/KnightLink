package knightlink.platform.ble

import kotlin.test.Test
import kotlin.test.assertTrue

class GattStatusTest {
    private fun assertMentions(
        status: Int,
        vararg fragments: String,
    ) {
        val text = GattStatus.describe(status)
        for (fragment in fragments) assertTrue(fragment in text, "status $status: '$fragment' missing from '$text'")
    }

    @Test
    fun wellKnownStatusesHaveReadableMessagesWithTheirNumber() {
        assertMentions(133, "generic", "133", "Bluetooth off and on")
        assertMentions(8, "timed out", "range", "status 8")
        assertMentions(19, "adapter ended", "status 19")
        assertMentions(22, "phone ended", "status 22")
        assertMentions(62, "could not be established", "status 62")
    }

    @Test
    fun unknownStatusesStillCarryTheirNumber() {
        assertMentions(4242, "4242")
    }

    @Test
    fun successIsSuccess() {
        assertMentions(0, "success")
    }

    @Test
    fun scanFailuresHaveReadableMessages() {
        assertTrue("already running" in ScanFailure.describe(1))
        assertTrue("too often" in ScanFailure.describe(6))
        assertTrue("99" in ScanFailure.describe(99))
    }
}
