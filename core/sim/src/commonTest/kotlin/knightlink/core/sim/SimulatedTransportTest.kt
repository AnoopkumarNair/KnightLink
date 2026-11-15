package knightlink.core.sim

import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimulatedTransportTest {
    private fun vehicle(): VirtualVehicle = VirtualVehicle(listOf(VirtualEcu(0x7E0, 0x7E8).apply { set("0105", "6B") }))

    @Test
    fun isASimulatedTransportByDefaultAndNothingIsOpenYet() {
        val transport = SimulatedTransport(vehicle = ::vehicle, config = VirtualElm327.Config())
        assertEquals(TransportKind.SIMULATED, transport.kind)
        assertEquals(0, transport.opens)
        assertNull(transport.current)
    }

    @Test
    fun theKindCanBeChosenSoACodePathThatBranchesOnItCanBeExercised() {
        assertEquals(TransportKind.BLE, SimulatedTransport(TransportKind.BLE, ::vehicle, VirtualElm327.Config()).kind)
    }

    @Test
    fun everyOpenGivesAFreshAdapterWithItsOwnStateLikeAPowerCycle() = runTest {
        val transport = SimulatedTransport(vehicle = ::vehicle, config = VirtualElm327.Config())

        val first = transport.open()
        first.write("ATE0\r".encodeToByteArray())
        assertEquals(listOf("ATE0"), transport.current!!.received)

        val second = transport.open()
        assertNotSame(first, second)
        assertSame(second, transport.current)
        assertEquals(emptyList(), transport.current!!.received)
        // Echo is on again: ATE0 from the previous "power-on" did not carry over.
        second.write("ATI\r".encodeToByteArray())
        second.close()
        assertEquals("ATI\r\nELM327 v2.3\r\n\r\n>", second.incoming.toList().joinToString("") { it.decodeToString() })
        assertEquals(2, transport.opens)
    }

    @Test
    fun theVehicleFactoryIsAskedOnEveryOpen() = runTest {
        var built = 0
        val transport = SimulatedTransport(vehicle = {
            built++
            vehicle()
        }, config = VirtualElm327.Config())
        transport.open()
        transport.open()
        assertEquals(2, built)
    }

    @Test
    fun aFactoryThatReturnsOneVehicleLetsAScenarioKeepChangingItAcrossReconnects() = runTest {
        val shared = vehicle()
        val transport = SimulatedTransport(vehicle = { shared }, config = VirtualElm327.Config())
        transport.open()
        shared.ecu(0x7E0).set("0105", "70")
        val second = transport.open()
        second.write("ATE0\r".encodeToByteArray())
        second.write("ATSH7E0\r".encodeToByteArray())
        second.write("0105\r".encodeToByteArray())
        second.close()
        // The second adapter sees the change made to the shared vehicle after the first one opened.
        assertTrue(second.incoming.toList().joinToString("") { it.decodeToString() }.contains("41 05 70"))
    }

    @Test
    fun theFirstOpensFailWithTransportExceptionAndStillCount() = runTest {
        val transport = SimulatedTransport(vehicle = ::vehicle, config = VirtualElm327.Config(), failFirstOpens = 2)
        assertFailsWith<TransportException> { transport.open() }
        assertNull(transport.current)
        assertFailsWith<TransportException> { transport.open() }
        assertEquals(2, transport.opens)

        val third = transport.open()
        assertSame(third, transport.current)
        assertEquals(3, transport.opens)
    }

    @Test
    fun theAdapterConfigIsPassedOn() = runTest {
        val transport = SimulatedTransport(vehicle = ::vehicle, config = VirtualElm327.Config(banner = "ELM327 v1.5"))
        val stream = transport.open()
        stream.write("ATI\r".encodeToByteArray())
        stream.close()
        assertEquals("ATI\r\nELM327 v1.5\r\n\r\n>", stream.incoming.toList().joinToString("") { it.decodeToString() })
    }
}
