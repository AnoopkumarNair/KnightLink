package knightlink.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedCoreDemoTest {
    @Test
    fun decodesTheHardCodedReplyThroughTheSharedCore() {
        assertEquals("engine.rpm = 799.0 rpm", SharedCoreDemo.decode())
    }

    @Test
    fun repliesWithoutADefinitionDecodeToNothing() {
        assertNull(SharedCoreDemo.decode("7E8 03 41 0D 28"))
    }
}
