package knightlink.app.voice

import android.content.res.Resources
import knightlink.app.R

/** The words of an [Announcement], from string resources. Kept apart from [ConnectionAnnouncer] so that it decides on facts only. */
fun Announcement.text(resources: Resources): String = when (this) {
    Announcement.Connected -> resources.getString(R.string.voice_connected)
    Announcement.WaitingForIgnition -> resources.getString(R.string.voice_waiting_for_ignition)
    Announcement.BusNotReachable -> resources.getString(R.string.voice_bus_not_reachable)
    Announcement.ConnectionLost -> resources.getString(R.string.voice_connection_lost)
    Announcement.Reconnected -> resources.getString(R.string.voice_reconnected)
    is Announcement.Failed ->
        if (reason.isBlank()) resources.getString(R.string.voice_failed_no_reason) else resources.getString(R.string.voice_failed, reason)
    Announcement.Disconnected -> resources.getString(R.string.voice_disconnected)
}
