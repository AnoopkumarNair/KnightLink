package knightlink.platform.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The runtime permissions Bluetooth LE needs, which differ between Android 12 (API 31) and older versions. */
// The 31+ permission constants are compile-time strings, so referencing them on older versions is harmless; which
// list applies is decided by an explicit sdkInt (a parameter, so it can be tested), not by what the compiler can see.
@SuppressLint("InlinedApi")
public object BlePermissions {
    /**
     * Permissions the app must hold (and ask the user for) to scan and connect on API level [sdkInt]: the "Nearby
     * devices" pair on API 31+, location on API 26-30, where Android only delivers BLE scan results to apps that can
     * see the user's location. Connecting on API 30 and older needs only install-time permissions.
     */
    public fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The subset of [required] that [context] does not hold yet; empty when everything needed is granted. */
    public fun missing(context: Context): List<String> = required().filter { !isGranted(context, it) }

    internal fun requiredToScan(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Before API 31 connecting needs only the install-time BLUETOOTH permission, so there is nothing to check at run time.
    internal fun requiredToConnect(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    internal fun isGranted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
