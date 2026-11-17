package knightlink.app.ble

/** Why Bluetooth cannot start: the permissions are still missing after the user was asked. */
enum class PermissionDenial {
    /** Denied once: Android will show its dialog again, so explain and offer to retry. */
    CAN_ASK_AGAIN,

    /** Blocked ("don't ask again", or denied twice): only the app's settings page can grant it now. */
    NEEDS_SETTINGS,
}

/** What to do after a permission request came back. Pure, so it is tested without Android. */
object PermissionPolicy {
    /**
     * @param stillMissing the required permissions that are not granted after the request
     * @param shouldExplain whether Android would still show its dialog for a permission
     *   (`shouldShowRequestPermissionRationale`: false after a denial means the user blocked it)
     * @return null when nothing is missing and the connection may go ahead
     */
    fun denial(stillMissing: List<String>, shouldExplain: (String) -> Boolean): PermissionDenial? = when {
        stillMissing.isEmpty() -> null
        stillMissing.all(shouldExplain) -> PermissionDenial.CAN_ASK_AGAIN
        else -> PermissionDenial.NEEDS_SETTINGS
    }
}

/** What the user asked for before the permissions were sorted out; done once they are. */
sealed interface PendingStart {
    data object Demo : PendingStart

    /** Open the picker to scan for an adapter. */
    data object ChooseAdapter : PendingStart

    /** Connect straight to a remembered adapter. */
    data class ConnectTo(val adapter: LastAdapter) : PendingStart
}
