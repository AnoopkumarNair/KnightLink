package knightlink.app.session

import knightlink.core.runtime.SessionState
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.ProbeStage
import knightlink.core.vehicle.Timing
import knightlink.voice.intents.LinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LinkStatusTest {
    private fun polling(): SessionState {
        val report = CapabilityReport(
            adapter = AdapterInfo("ELM327 v2.3", null, null, null, null),
            timing = Timing(probeMs = 1000.0, benchmarkSamples = 0, medianRoundTripMs = null, estimatedRequestsPerSecond = null),
            ecus = emptyList(),
            derived = DerivedFacts.of(emptyList()),
        )
        return SessionState.Polling(report, DefinitionCoverage(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun noSessionIsNotConnected() {
        assertEquals(LinkStatus.NOT_CONNECTED, null.toLinkStatus())
    }

    @Test
    fun pollingIsLive() {
        assertEquals(LinkStatus.LIVE, polling().toLinkStatus())
    }

    @Test
    fun theStepsBeforePollingAreConnecting() {
        assertEquals(LinkStatus.CONNECTING, SessionState.Idle.toLinkStatus())
        assertEquals(LinkStatus.CONNECTING, SessionState.Connecting(2).toLinkStatus())
        assertEquals(LinkStatus.CONNECTING, SessionState.Initialising.toLinkStatus())
        for (stage in ProbeStage.entries) assertEquals(LinkStatus.CONNECTING, SessionState.Probing(stage).toLinkStatus(), stage.name)
    }

    @Test
    fun aSilentCarIsWaitingForTheIgnition() {
        assertEquals(LinkStatus.WAITING_FOR_IGNITION, SessionState.WaitingForIgnition(3, null).toLinkStatus())
        assertEquals(LinkStatus.WAITING_FOR_IGNITION, SessionState.WaitingForIgnition(3, "CAN_ERROR").toLinkStatus())
    }

    @Test
    fun recoveringAndReconnectingAreReconnecting() {
        assertEquals(LinkStatus.RECONNECTING, SessionState.Recovering(1, "no answer").toLinkStatus())
        assertEquals(LinkStatus.RECONNECTING, SessionState.Reconnecting(1, 5.seconds, "link lost").toLinkStatus())
    }

    @Test
    fun failedAndStoppedAreNotConnected() {
        assertEquals(LinkStatus.NOT_CONNECTED, SessionState.Failed("gone").toLinkStatus())
        assertEquals(LinkStatus.NOT_CONNECTED, SessionState.Stopped.toLinkStatus())
    }
}
