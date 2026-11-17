package knightlink.platform.ble

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class BlePermissionsTest {
    @Test
    fun android12AndNewerNeedTheNearbyDevicesPermissions() {
        val expected = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(expected, BlePermissions.required(31))
        assertEquals(expected, BlePermissions.required(36))
    }

    @Test
    fun android11AndOlderNeedLocationForScanning() {
        val expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(expected, BlePermissions.required(26))
        assertEquals(expected, BlePermissions.required(30))
    }

    @Test
    fun scanAndConnectNeedsAreSplitByVersion() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), BlePermissions.requiredToScan(33))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.requiredToScan(28))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BlePermissions.requiredToConnect(31))
        // Nothing to ask for at run time before API 31.
        assertEquals(emptyList(), BlePermissions.requiredToConnect(30))
    }
}
