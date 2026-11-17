package knightlink.platform.ble

/** Turns the numeric status codes of Android's GATT callbacks into text a user can act on. Pure, so it is unit-tested. */
internal object GattStatus {
    const val SUCCESS = 0
    private const val INSUFFICIENT_AUTHENTICATION = 5
    private const val CONNECTION_TIMEOUT = 8
    private const val INSUFFICIENT_ENCRYPTION = 15
    private const val TERMINATED_BY_PEER = 19
    private const val TERMINATED_BY_LOCAL_HOST = 22
    private const val FAILED_TO_ESTABLISH = 62
    private const val GENERIC_ERROR = 133
    private const val GATT_FAILURE = 257

    /** One-line description of [status], always including the number so it can be searched for and reported. */
    fun describe(status: Int): String =
        when (status) {
            SUCCESS -> "success"
            INSUFFICIENT_AUTHENTICATION -> "the adapter requires pairing first (GATT status 5)"
            CONNECTION_TIMEOUT -> "the connection timed out; the adapter stopped responding or went out of range (GATT status 8)"
            INSUFFICIENT_ENCRYPTION -> "the adapter requires an encrypted link (GATT status 15)"
            TERMINATED_BY_PEER -> "the adapter ended the connection (GATT status 19)"
            TERMINATED_BY_LOCAL_HOST -> "the phone ended the connection (GATT status 22)"
            FAILED_TO_ESTABLISH -> "the connection could not be established (GATT status 62)"
            GENERIC_ERROR ->
                "generic Bluetooth error (GATT status 133); this is often a glitch in the phone's Bluetooth stack, " +
                    "so try again, or turn Bluetooth off and on"
            GATT_FAILURE -> "the Bluetooth stack reported a failure (GATT status 257)"
            else -> "Bluetooth error (GATT status $status)"
        }
}
