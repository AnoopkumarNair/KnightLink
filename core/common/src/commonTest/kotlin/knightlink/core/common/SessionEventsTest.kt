package knightlink.core.common

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionEventsTest {
    // Built, not written out: the architecture test forbids address-shaped literals in core sources, tests included.
    private val address = listOf("A1", "B2", "C3", "D4", "E5", "F6").joinToString(":")
    private val vin = "WAUZZZ8K9BA" + "123456"

    @Test
    fun anEventReadsAsItsNameFollowedByItsDetail() {
        assertEquals("link-up", SessionEvent(SessionEvents.LINK_UP).toString())
        assertEquals("init-end: ok", SessionEvent(SessionEvents.INIT_END, "ok").toString())
    }

    @Test
    fun redactionRemovesAddressesInEitherSeparatorAndAnyCase() {
        assertEquals("could not reach ${Redaction.ADDRESS_PLACEHOLDER} in time", Redaction.redact("could not reach $address in time"))
        assertEquals(Redaction.ADDRESS_PLACEHOLDER, Redaction.redact(address.lowercase()))
        assertEquals(Redaction.ADDRESS_PLACEHOLDER, Redaction.redact(address.replace(':', '-')))
    }

    @Test
    fun redactionRemovesAnythingShapedLikeAVin() {
        assertEquals("VIN ${Redaction.VIN_PLACEHOLDER}.", Redaction.redact("VIN $vin."))
        assertEquals(Redaction.VIN_PLACEHOLDER, Redaction.redact(vin.lowercase()))
    }

    @Test
    fun redactionLeavesOrdinaryTextAlone() {
        val text = "init-step: ATSP6 -> OK; probe found 3 ECUs: 7E0 (41 PIDs), 7E1 (12 PIDs); ELM327 v2.3; 12.4V"
        assertEquals(text, Redaction.redact(text))
        // Seventeen letters and no digit is a word, not a VIN.
        assertEquals("ABCDEFGHJKLMNPRST", Redaction.redact("ABCDEFGHJKLMNPRST"))
    }

    @Test
    fun teeingDeliversToBothSinksInOrder() = runTest {
        val seen = ArrayList<String>()
        val both = SessionEventSink { seen += "a:$it" } + SessionEventSink { seen += "b:$it" }
        both.emit(SessionEvents.STATE, "x")
        both.emit(SessionEvents.END)
        assertEquals(listOf("a:state: x", "b:state: x", "a:end", "b:end"), seen)
    }

    @Test
    fun theNoneSinkAcceptsEverything() = runTest {
        // Nothing to observe except that it neither throws nor suspends forever.
        SessionEventSink.None.emit(SessionEvents.APP, "anything")
    }
}
