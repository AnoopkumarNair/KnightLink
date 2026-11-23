package knightlink.app.session

import knightlink.core.runtime.SessionState
import knightlink.voice.intents.LinkStatus

/**
 * What a spoken answer should assume about the link, from the session's state. Null means there is no session at all.
 * Idle with a session present is the moment right before it connects, so it counts as connecting.
 */
fun SessionState?.toLinkStatus(): LinkStatus = when (this) {
    null -> LinkStatus.NOT_CONNECTED
    is SessionState.Polling -> LinkStatus.LIVE
    SessionState.Idle, is SessionState.Connecting, SessionState.Initialising, is SessionState.Negotiating, is SessionState.Probing -> LinkStatus.CONNECTING
    is SessionState.WaitingForIgnition, is SessionState.VehicleAsleep -> LinkStatus.WAITING_FOR_IGNITION
    is SessionState.Recovering, is SessionState.Reconnecting -> LinkStatus.RECONNECTING
    is SessionState.Failed, is SessionState.CannotReadVehicle, SessionState.Stopped -> LinkStatus.NOT_CONNECTED
}
