package knightlink.core.sim

import knightlink.core.transport.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualElm327DisconnectTest {
    private fun adapter(config: VirtualElm327.Config = VirtualElm327.Config()): VirtualElm327 {
        val ecm = VirtualEcu(0x7E0, 0x7E8).apply { set("0105", "6B") }
        return VirtualElm327(VirtualVehicle(listOf(ecm)), config)
    }

    @Test
    fun disconnectEndsTheIncomingFlowNormally() = runTest {
        val adapter = adapter()
        adapter.disconnect()
        assertEquals(emptyList(), adapter.incoming.toList())
    }

    @Test
    fun disconnectWithACauseFailsTheIncomingFlowWithIt() = runTest {
        val adapter = adapter()
        adapter.disconnect(IllegalStateException("out of range"))
        val error = assertFailsWith<IllegalStateException> { adapter.incoming.toList() }
        assertEquals("out of range", error.message)
    }

    @Test
    fun bytesSentBeforeTheDisconnectAreStillDelivered() = runTest {
        val adapter = adapter()
        adapter.write("ATI\r".encodeToByteArray())
        adapter.disconnect()
        assertEquals("ATI\r\nELM327 v2.3\r\n\r\n>", adapter.incoming.toList().joinToString("") { it.decodeToString() })
    }

    @Test
    fun writesAfterADisconnectThrowTransportExceptionAndAreNotRecorded() = runTest {
        val adapter = adapter()
        adapter.disconnect()
        assertFailsWith<TransportException> { adapter.write("ATI\r".encodeToByteArray()) }
        assertEquals(emptyList(), adapter.received)
    }

    @Test
    fun writesAfterCloseThrowTransportExceptionToo() = runTest {
        val adapter = adapter()
        adapter.close()
        assertFailsWith<TransportException> { adapter.write("ATI\r".encodeToByteArray()) }
    }

    @Test
    fun aCommandThatIsBeingAnsweredWhenTheLinkDropsFailsWithTransportException() = runTest {
        val adapter = adapter(VirtualElm327.Config(responseDelay = 45.milliseconds))
        val write = async { runCatching { adapter.write("ATI\r".encodeToByteArray()) } }
        runCurrent()
        adapter.disconnect()
        val error = write.await().exceptionOrNull()
        assertTrue(error is TransportException, "was $error")
        assertEquals(45L, currentTime)
        assertEquals(emptyList(), adapter.incoming.toList())
    }

    @Test
    fun disconnectingTwiceOrAfterCloseIsHarmless() = runTest {
        val adapter = adapter()
        adapter.close()
        adapter.disconnect()
        adapter.disconnect(IllegalStateException("late"))
        assertEquals(emptyList(), adapter.incoming.toList())
    }
}
