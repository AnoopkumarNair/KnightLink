package knightlink.app.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermissionPolicyTest {
    @Test
    fun nothingMissingMeansGoAhead() {
        assertNull(PermissionPolicy.denial(emptyList()) { error("must not be asked") })
    }

    @Test
    fun aPermissionAndroidWouldAskAboutAgainCanBeRetried() {
        assertEquals(PermissionDenial.CAN_ASK_AGAIN, PermissionPolicy.denial(listOf("scan", "connect")) { true })
    }

    @Test
    fun aBlockedPermissionNeedsTheSettingsPage() {
        assertEquals(PermissionDenial.NEEDS_SETTINGS, PermissionPolicy.denial(listOf("scan")) { false })
    }

    @Test
    fun oneBlockedPermissionAmongSeveralNeedsTheSettingsPage() {
        assertEquals(PermissionDenial.NEEDS_SETTINGS, PermissionPolicy.denial(listOf("scan", "connect")) { it == "scan" })
    }
}
