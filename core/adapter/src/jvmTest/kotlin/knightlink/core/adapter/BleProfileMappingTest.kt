package knightlink.core.adapter

import knightlink.core.transport.ble.GattCharacteristic
import knightlink.core.transport.ble.GattProfileResolver
import knightlink.core.transport.ble.GattService
import knightlink.core.transport.ble.Resolution
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BleProfileMappingTest {
    private val dir = File(System.getProperty("knightlink.repoRoot"), "definitions/adapters")

    @Test
    fun mapsEveryFieldOfTheProfile() {
        val ble =
            AdapterDefinition.BleProfile(
                names = listOf("n"),
                serviceUuid = "s",
                writeCharacteristicUuid = "w",
                notifyCharacteristicUuid = "r",
                ignoreServiceUuids = listOf("i"),
            )

        val mapped = ble.toSerialProfile()

        assertEquals("s", mapped.serviceUuid)
        assertEquals("w", mapped.writeCharacteristicUuid)
        assertEquals("r", mapped.notifyCharacteristicUuid)
        assertEquals(listOf("i"), mapped.ignoreServiceUuids)
    }

    @Test
    fun everyBleAdapterDefinitionResolvesAgainstAPeripheralBuiltFromItsOwnProfile() {
        val definitions = dir.listFiles { f -> f.extension == "json" }.orEmpty().map { AdapterDefinition.parse(it.readText()) }.filter { it.ble != null }
        assertTrue(definitions.isNotEmpty())
        for (definition in definitions) {
            val profile = checkNotNull(definition.ble).toSerialProfile()
            val full = GattCharacteristic(profile.writeCharacteristicUuid, true, true, true, false)
            // Every ignored service looks perfectly usable, as the echo service of the reference adapter does.
            val decoys = profile.ignoreServiceUuids.map {
                GattService(it, listOf(full, GattCharacteristic(profile.notifyCharacteristicUuid, true, true, true, false)))
            }
            val real = GattService(profile.serviceUuid, listOf(full, GattCharacteristic(profile.notifyCharacteristicUuid, true, true, true, false)))

            val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(decoys + real, profile), definition.id)

            assertSame(real, found.service, "${definition.id}: an ignored service was chosen")
        }
    }
}
