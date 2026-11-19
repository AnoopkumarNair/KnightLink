package knightlink.app.voice

import knightlink.app.session.ConnectionStatus
import knightlink.core.vehicle.ProbeStage
import knightlink.voice.api.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionAnnouncerTest {
    private class Rig(scope: TestScope, enabled: () -> Boolean) {
        val said = mutableListOf<Announcement>()
        val statuses = MutableSharedFlow<ConnectionStatus>(extraBufferCapacity = 64)
        private val announcer = ConnectionAnnouncer(isEnabled = enabled, announce = { said += it })

        init {
            scope.backgroundScope.launch { announcer.collect(statuses) }
            scope.runCurrent()
        }

        suspend fun send(vararg all: ConnectionStatus, then: kotlin.time.Duration? = null, scope: TestScope) {
            all.forEach {
                statuses.emit(it)
                scope.runCurrent()
            }
            then?.let { scope.advanceTimeBy(it) }
            scope.runCurrent()
        }
    }

    private val connecting = ConnectionStatus.Connecting(1)
    private val probing = ConnectionStatus.Probing(ProbeStage.ADAPTER)
    private val reconnecting = ConnectionStatus.Reconnecting(attempt = 1, retryInSeconds = 2, cause = "link dropped")
    private val recovering = ConnectionStatus.Recovering(attempt = 1, reason = "no answer")

    private fun TestScope.rig(enabled: () -> Boolean = { true }) = Rig(this, enabled)

    @Test
    fun theWayToAConnectionIsSilentAndTheFirstLiveSaysConnected() = runTest {
        val rig = rig()

        rig.send(connecting, ConnectionStatus.Initialising, probing, scope = this)
        assertEquals(emptyList<Announcement>(), rig.said)

        rig.send(ConnectionStatus.Live, scope = this)
        assertEquals(listOf<Announcement>(Announcement.Connected), rig.said)
    }

    @Test
    fun waitingForTheIgnitionIsSaidOnceWhateverTheProbeCount() = runTest {
        val rig = rig()

        rig.send(connecting, ConnectionStatus.WaitingForIgnition(1, busFault = false), scope = this)
        rig.send(ConnectionStatus.WaitingForIgnition(2, busFault = false), ConnectionStatus.WaitingForIgnition(3, busFault = false), scope = this)

        assertEquals(listOf<Announcement>(Announcement.WaitingForIgnition), rig.said)
    }

    @Test
    fun aBusFaultIsSaidInItsOwnWords() = runTest {
        val rig = rig()

        rig.send(connecting, ConnectionStatus.WaitingForIgnition(1, busFault = true), scope = this)

        assertEquals(listOf<Announcement>(Announcement.BusNotReachable), rig.said)
    }

    @Test
    fun liveAfterWaitingForTheIgnitionStillSaysConnected() = runTest {
        val rig = rig()

        rig.send(connecting, ConnectionStatus.WaitingForIgnition(1, busFault = false), ConnectionStatus.Live, scope = this)

        assertEquals(listOf<Announcement>(Announcement.WaitingForIgnition, Announcement.Connected), rig.said)
    }

    @Test
    fun anOutageLongEnoughIsSaidOnceAndTheReturnIsReconnected() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, scope = this)

        rig.send(reconnecting, then = 3.seconds, scope = this)
        assertEquals(listOf<Announcement>(Announcement.Connected), rig.said)
        rig.send(then = 2.seconds, scope = this)
        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.ConnectionLost), rig.said)

        rig.send(ConnectionStatus.Live, scope = this)
        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.ConnectionLost, Announcement.Reconnected), rig.said)
    }

    @Test
    fun aReconnectLoopSaysConnectionLostOnlyOnce() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, scope = this)

        repeat(10) { attempt ->
            rig.send(
                ConnectionStatus.Reconnecting(attempt, 2, "link dropped"),
                connecting,
                ConnectionStatus.Initialising,
                probing,
                recovering,
                then = 3.seconds,
                scope = this,
            )
        }

        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.ConnectionLost), rig.said)
    }

    @Test
    fun aShortBlipIsNotMentionedNorItsEnd() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, scope = this)

        rig.send(reconnecting, then = 2.seconds, scope = this)
        rig.send(ConnectionStatus.Live, then = 10.seconds, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected), rig.said)
    }

    @Test
    fun aNewOutageAfterARecoveryIsSaidAgain() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, scope = this)
        rig.send(recovering, then = 5.seconds, scope = this)
        rig.send(ConnectionStatus.Live, scope = this)

        rig.send(recovering, then = 5.seconds, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.ConnectionLost, Announcement.Reconnected, Announcement.ConnectionLost), rig.said)
    }

    @Test
    fun aFailureIsSaidWithAShortReasonAndNoDisconnectedFollows() = runTest {
        val rig = rig()
        rig.send(connecting, scope = this)

        rig.send(ConnectionStatus.Failed("The adapter did not answer. Check that it is plugged in and try again."), ConnectionStatus.Idle, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Failed("The adapter did not answer")), rig.said)
    }

    @Test
    fun aFailureEndsAPendingOutage() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, reconnecting, scope = this)

        rig.send(ConnectionStatus.Failed("gave up"), then = 10.seconds, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.Failed("gave up")), rig.said)
    }

    @Test
    fun goingIdleAfterALiveConnectionSaysDisconnected() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, ConnectionStatus.Idle, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.Disconnected), rig.said)
    }

    @Test
    fun goingIdleAfterAStartThatSaidNothingStaysSilent() = runTest {
        val rig = rig()

        rig.send(connecting, ConnectionStatus.Idle, scope = this)

        assertEquals(emptyList<Announcement>(), rig.said)
    }

    @Test
    fun theNextConnectionStartsFresh() = runTest {
        val rig = rig()
        rig.send(ConnectionStatus.Live, ConnectionStatus.Idle, connecting, ConnectionStatus.Live, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected, Announcement.Disconnected, Announcement.Connected), rig.said)
    }

    @Test
    fun nothingIsSaidWhileDisabledAndNothingStaleAfterEnabling() = runTest {
        var enabled = false
        val rig = rig { enabled }

        rig.send(connecting, ConnectionStatus.Live, reconnecting, then = 10.seconds, scope = this)
        rig.send(ConnectionStatus.Failed("x"), ConnectionStatus.Idle, scope = this)
        assertEquals(emptyList<Announcement>(), rig.said)

        enabled = true
        rig.send(connecting, ConnectionStatus.Live, reconnecting, then = 1.seconds, scope = this)
        rig.send(ConnectionStatus.Live, scope = this)
        // The first live of the second connection was heard; the blip was shorter than the delay.
        assertEquals(listOf<Announcement>(Announcement.Connected), rig.said)
    }

    @Test
    fun aLossWhileDisabledIsNotFollowedByReconnectedAfterEnabling() = runTest {
        var enabled = true
        val rig = rig { enabled }
        rig.send(ConnectionStatus.Live, scope = this)

        enabled = false
        rig.send(reconnecting, then = 10.seconds, scope = this)
        enabled = true
        rig.send(ConnectionStatus.Live, scope = this)

        assertEquals(listOf<Announcement>(Announcement.Connected), rig.said)
    }

    @Test
    fun theReasonIsTheFirstSentenceTrimmedAndAtMostEightyCharacters() {
        assertEquals("Timed out", ConnectionAnnouncer.shortReason("  Timed out. Then more.  "))
        assertEquals("Version 1.2 is not supported", ConnectionAnnouncer.shortReason("Version 1.2 is not supported"))
        assertEquals("first line", ConnectionAnnouncer.shortReason("first line\nsecond line"))
        assertEquals("", ConnectionAnnouncer.shortReason("   "))
        val long = ConnectionAnnouncer.shortReason("word ".repeat(40))
        assertTrue(long.length <= 80, long)
        assertTrue(long.endsWith("word"), long)
        val unbroken = ConnectionAnnouncer.shortReason("x".repeat(200))
        assertEquals(80, unbroken.length)
    }

    @Test
    fun theUtteranceIsAStatusLineOfTheConnectionCategoryThatExpires() {
        val line = statusUtterance("Hello.")

        assertEquals(Priority.STATUS, line.priority)
        assertEquals("connection", line.category)
        assertEquals(20.seconds, line.maxAge)
    }
}
