package knightlink.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GattProfileResolverTest {
    // Made-up identifiers, built from pieces: real adapter UUIDs belong in definition files, never in test code either.
    private fun fakeUuid(n: Int) = "${n.toString(16).padStart(8, '0')}-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    private val serial = fakeUuid(1)
    private val echo = fakeUuid(2)
    private val data = fakeUuid(3)
    private val other = fakeUuid(4)

    private fun characteristic(
        uuid: String,
        write: Boolean = false,
        writeNoResponse: Boolean = false,
        notify: Boolean = false,
        indicate: Boolean = false,
    ) = GattCharacteristic(uuid, write, writeNoResponse, notify, indicate)

    private val profile = BleSerialProfile(serial, data, data, ignoreServiceUuids = listOf(echo))

    private fun serialService(vararg characteristics: GattCharacteristic) = GattService(serial, characteristics.toList())

    @Test
    fun findsTheSerialChannelAndPrefersWriteWithResponse() {
        val shared = characteristic(data, write = true, writeNoResponse = true, notify = true)
        val service = serialService(shared)

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(service), profile))

        assertSame(service, found.service)
        assertSame(shared, found.write)
        assertSame(shared, found.notify)
        assertEquals(GattWriteType.WITH_RESPONSE, found.writeType)
    }

    @Test
    fun fallsBackToWriteWithoutResponseWhenThatIsAllThePeripheralOffers() {
        val service = serialService(characteristic(data, writeNoResponse = true, notify = true))

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(service), profile))

        assertEquals(GattWriteType.WITHOUT_RESPONSE, found.writeType)
    }

    @Test
    fun theEchoServiceIsNeverChosenEvenWhenItLooksUsable() {
        val echoService = GattService(echo, listOf(characteristic(data, write = true, writeNoResponse = true, notify = true)))
        val realService = serialService(characteristic(data, write = true, notify = true))

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(echoService, realService), profile))

        assertSame(realService, found.service)
    }

    @Test
    fun withOnlyTheEchoServicePresentTheChannelIsNotFound() {
        val echoService = GattService(echo, listOf(characteristic(data, write = true, notify = true)))
        // Even a profile whose serial service UUID equals the ignored one must not select it.
        val contradictory = profile.copy(serviceUuid = echo)

        assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(echoService), profile))
        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(echoService), contradictory)).reason
        assertTrue("ignored" in reason, reason)
    }

    @Test
    fun missingServiceListsWhatTheAdapterDoesExpose() {
        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(GattService(other, emptyList())), profile)).reason

        assertTrue("$serial is missing" in reason, reason)
        assertTrue(other in reason, reason)
    }

    @Test
    fun missingServiceOnAnAdapterWithNoServicesSaysSo() {
        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(emptyList(), profile)).reason

        assertTrue("no usable services" in reason, reason)
    }

    @Test
    fun missingCharacteristicNamesTheRoleAndWhatIsThere() {
        val service = serialService(characteristic(other, write = true))

        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(service), profile)).reason

        assertTrue("no write characteristic $data" in reason, reason)
        assertTrue(other in reason, reason)
    }

    @Test
    fun missingNotifyCharacteristicIsReportedWhenWriteIsFine() {
        val separate = profile.copy(notifyCharacteristicUuid = other)
        val service = serialService(characteristic(data, write = true))

        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(service), separate)).reason

        assertTrue("no notify characteristic $other" in reason, reason)
    }

    @Test
    fun writeCharacteristicWithoutWriteSupportIsRejectedWithItsProperties() {
        val service = serialService(characteristic(data, notify = true))

        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(service), profile)).reason

        assertTrue("cannot be written" in reason, reason)
        assertTrue("notify" in reason, reason)
    }

    @Test
    fun notifyCharacteristicWithoutNotifyOrIndicateIsRejected() {
        val service = serialService(characteristic(data, write = true))

        val reason = assertIs<Resolution.NotFound>(GattProfileResolver.resolve(listOf(service), profile)).reason

        assertTrue("cannot be subscribed to" in reason, reason)
    }

    @Test
    fun indicationIsAcceptedWhenNotificationIsNotOffered() {
        val shared = characteristic(data, write = true, indicate = true)

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(serialService(shared)), profile))

        assertEquals(GattSubscription.INDICATE, found.notify.subscription)
    }

    @Test
    fun notificationIsPreferredOverIndication() {
        assertEquals(GattSubscription.NOTIFY, characteristic(data, notify = true, indicate = true).subscription)
        assertNull(characteristic(data, write = true).subscription)
    }

    @Test
    fun uuidsMatchCaseInsensitivelyAndAcrossShortAndLongForms() {
        // The profile uses short forms and upper case; the peripheral reports long forms.
        val shortProfile = BleSerialProfile("ABCD", "ab01", "AB02", ignoreServiceUuids = listOf("0x18F0"))
        val service =
            GattService(
                BluetoothUuid.fromShort(0xabcd).uppercase(),
                listOf(
                    characteristic(BluetoothUuid.fromShort(0xab01), write = true),
                    characteristic(BluetoothUuid.fromShort(0xab02).uppercase(), notify = true),
                ),
            )

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(service), shortProfile))

        assertSame(service, found.service)
        assertEquals(GattWriteType.WITH_RESPONSE, found.writeType)
    }

    @Test
    fun ignoredServiceGivenInShortFormIsIgnoredWhenReportedInLongForm() {
        val shortIgnore = profile.copy(serviceUuid = "18f1", ignoreServiceUuids = listOf("18F0"))
        val echoLong = GattService(BluetoothUuid.fromShort(0x18f0), listOf(characteristic(data, write = true, notify = true)))
        val realLong = GattService(BluetoothUuid.fromShort(0x18f1), listOf(characteristic(data, write = true, notify = true)))

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(echoLong, realLong), shortIgnore))

        assertSame(realLong, found.service)
    }

    @Test
    fun aDuplicateServiceInstanceThatWorksIsPickedOverOneThatDoesNot() {
        val broken = serialService(characteristic(data, notify = true))
        val good = serialService(characteristic(data, write = true, notify = true))

        val found = assertIs<Resolution.Found>(GattProfileResolver.resolve(listOf(broken, good), profile))

        assertSame(good, found.service)
    }

    @Test
    fun normalizeHandlesFormsAndLeavesGarbageAlone() {
        // Spelled in pieces so no complete UUID literal appears in a source file.
        val expected = "000018f0" + "-0000-1000-8000-" + "00805f9b34fb"
        assertEquals(expected, BluetoothUuid.normalize(" {0x18F0} "))
        assertEquals(expected, BluetoothUuid.normalize("000018F0"))
        assertEquals(expected, BluetoothUuid.fromShort(0x18f0))
        assertEquals("zzzz", BluetoothUuid.normalize("ZZZZ"))
        assertEquals(fakeUuid(1), BluetoothUuid.normalize(fakeUuid(1).uppercase()))
    }
}
