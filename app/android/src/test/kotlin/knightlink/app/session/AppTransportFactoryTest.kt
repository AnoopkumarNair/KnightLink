package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.adapter.AdapterDefinition
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import knightlink.core.transport.ble.BleSerialProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppTransportFactoryTest {
    private class FakeTransport(override val kind: TransportKind) : Transport {
        override suspend fun open(): ByteStream = error("not opened in these tests")
    }

    private val assets = RepoAssetSource()
    private val adapter = DefinitionStore(assets).adapter(AppConfig.load(assets).adapterDefinition)

    private class Calls {
        val demo = ArrayList<ConnectionTarget>()
        val ble = ArrayList<Pair<String, BleSerialProfile>>()
    }

    private fun factory(calls: Calls, definition: () -> AdapterDefinition = { adapter }, bleTransport: Transport = FakeTransport(TransportKind.BLE)) =
        AppTransportFactory(
            demo = TransportFactory { target ->
                calls.demo += target
                FakeTransport(TransportKind.SIMULATED)
            },
            adapter = definition,
            ble = { address, profile ->
                calls.ble += address to profile
                bleTransport
            },
        )

    @Test
    fun theDemoIsDelegatedAndTheBluetoothSeamIsNotTouched() {
        val calls = Calls()
        val target = ConnectionTarget.Demo("sim/anything")

        val transport = factory(calls).create(target)

        assertEquals(TransportKind.SIMULATED, transport.kind)
        assertEquals(listOf<ConnectionTarget>(target), calls.demo)
        assertTrue(calls.ble.isEmpty())
    }

    @Test
    fun aBluetoothTargetBuildsTheTransportWithTheAddressAndTheProfileOfTheDefinition() {
        val calls = Calls()
        val bleTransport = FakeTransport(TransportKind.BLE)

        val transport = factory(calls, bleTransport = bleTransport).create(ConnectionTarget.Ble("device-1"))

        assertSame(bleTransport, transport)
        assertTrue(calls.demo.isEmpty())
        val (address, profile) = calls.ble.single()
        assertEquals("device-1", address)
        val ble = requireNotNull(adapter.ble) { "the configured adapter definition must have a BLE profile" }
        assertEquals(ble.serviceUuid, profile.serviceUuid)
        assertEquals(ble.writeCharacteristicUuid, profile.writeCharacteristicUuid)
        assertEquals(ble.notifyCharacteristicUuid, profile.notifyCharacteristicUuid)
        assertEquals(ble.ignoreServiceUuids, profile.ignoreServiceUuids)
    }

    @Test
    fun theDefinitionIsReadWhenTheTransportIsCreatedNotWhenTheFactoryIs() {
        val calls = Calls()
        var reads = 0
        val factory = factory(calls, definition = { adapter.also { reads++ } })
        assertEquals(0, reads)

        factory.create(ConnectionTarget.Ble("a"))
        factory.create(ConnectionTarget.Ble("b"))
        factory.create(ConnectionTarget.Demo("sim/x"))

        assertEquals(2, reads)
    }

    @Test
    fun aDefinitionWithoutABluetoothProfileIsRefusedWithAClearMessage() {
        val calls = Calls()
        val withoutBle = adapter.copy(ble = null)

        val e = assertFailsWith<UnsupportedOperationException> { factory(calls, definition = { withoutBle }).create(ConnectionTarget.Ble("device-1")) }

        assertTrue(adapter.id in e.message.orEmpty(), e.message)
        assertTrue("Bluetooth" in e.message.orEmpty(), e.message)
        assertTrue(calls.ble.isEmpty())
    }

    @Test
    fun aDemoNeedsNoBluetoothProfile() {
        val calls = Calls()

        factory(calls, definition = { error("must not be read for the demo") }).create(ConnectionTarget.Demo("sim/x"))

        assertEquals(1, calls.demo.size)
    }

    @Test
    fun aMissingDefinitionFileSurfacesAsItsOwnError() {
        val calls = Calls()

        assertFailsWith<IllegalStateException> { factory(calls, definition = { error("no such definition") }).create(ConnectionTarget.Ble("device-1")) }
    }
}
