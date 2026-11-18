package knightlink.core.rules

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.SimulationFile
import knightlink.core.sim.VirtualElm327
import knightlink.core.telemetry.TelemetrySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The rules on the real stack: scenario file -> virtual car -> virtual adapter -> session (driver, probe, polling) -> telemetry -> rules, all on
 * virtual time. The DPF story of the scenario has to come out as exactly two spoken sentences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEndToEndTest {
    private val simulation = SimulationFile.parse(Repo.text("fixtures/sim/harrier-dpf-regen.json"))
    private val initScript = AdapterDefinition.parse(Repo.text("definitions/adapters/vgate-icar-pro-2s.json")).initScript
    private val catalog = Repo.catalog(simulation.definition)
    private val config = Repo.rules()
    private val book = Repo.phrases()

    private fun linkOf(state: SessionState): LinkStatus =
        when (state) {
            is SessionState.Polling -> LinkStatus.LIVE
            is SessionState.WaitingForIgnition -> LinkStatus.NO_IGNITION
            else -> LinkStatus.LINK_DOWN
        }

    @Test
    fun theDpfRegenerationOfTheScenarioIsSpokenAsStartAndFinishAndNothingElse() = runTest {
        val vehicle = simulation.buildVehicle()
        val transport = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        // The scenario's DPF is the story, so it is polled faster than the rest, as in the session tests.
        val sessionConfig = VehicleSession.Config(
            driver = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO),
            pollPeriod = { parameter -> if (parameter.signals.any { it.definition.id.startsWith("dpf.") }) 500.milliseconds else 1.seconds },
        )
        val session = VehicleSession(transport, initScript, catalog, backgroundScope, testTimeSource, sessionConfig)
        val engine = RulesEngine(config, CatalogSignalTrust(catalog))

        val states = ArrayList<Pair<Duration, RulesState>>()
        fun evaluate(snapshot: TelemetrySnapshot) {
            val now = currentTime.milliseconds
            states += now to engine.evaluate(snapshot, now, linkOf(session.state.value))
        }
        // On every new snapshot, and on a one second tick for the time-based transitions.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.telemetry.collect(::evaluate) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            while (true) {
                delay(1.seconds)
                evaluate(session.telemetry.value)
            }
        }
        // The scenario's clock starts together with the session's.
        backgroundScope.launch { simulation.play(vehicle) }

        session.start()
        advanceTimeBy(40.seconds)
        runCurrent()

        val spoken = states.flatMap { (now, state) -> state.announcements.map { now to it } }
        assertEquals(
            listOf("dpf.regen_active", "dpf.regen_completed"),
            spoken.map { it.second.id },
            "everything spoken: " + spoken.joinToString { "${it.second.id}@${it.first}" },
        )
        val (startedAt, start) = spoken[0]
        val (finishedAt, finish) = spoken[1]
        assertTrue(startedAt in 8.seconds..10.seconds, "regeneration announced at $startedAt")
        assertTrue(finishedAt in 20.seconds..23.seconds, "completion announced at $finishedAt")
        assertEquals("The DPF is cleaning itself; keep driving and don't switch off yet.", book.render(start.phrase))
        assertEquals(Phrase("dpf.regen_completed", mapOf("sootBefore" to "100", "sootAfter" to "18")), finish.phrase)
        assertEquals("DPF cleaning finished: soot is down from 100 to 18 percent.", book.render(finish.phrase))

        // No warning of any kind during the whole story: no soot alert, no interruption, no AdBlue, no coolant.
        val everActive = states.flatMap { it.second.alerts }.map { it.id }.toSet()
        assertEquals(setOf("dpf.regen_active", "dpf.regen_completed"), everActive)

        // The filter's phases, in order and without flapping.
        val phases = states.map { it.second.dpf.phase }.filter { it != DpfPhase.UNKNOWN }.fold(emptyList<DpfPhase>()) { all, phase ->
            if (all.lastOrNull() == phase) all else all + phase
        }
        assertEquals(listOf(DpfPhase.SOOT_RISING, DpfPhase.REGEN_ACTIVE, DpfPhase.REGEN_COMPLETED), phases)

        // Not yet while it cleans, safe once it has finished and the exhaust has cooled.
        for ((now, state) in states.filter { it.first in 10.seconds..19.seconds }) {
            assertEquals(SwitchOffReason.REGEN_ACTIVE, assertIs<SwitchOffAdvice.NotYet>(state.switchOff).reason, "at $now")
        }
        val last = states.last().second
        assertEquals(SwitchOffAdvice.Safe, last.switchOff)
        assertEquals(DpfPhase.REGEN_COMPLETED, last.dpf.phase)
        assertEquals(emptySet(), last.staleInputs)
        assertEquals(listOf(Phrase("answer.warnings.none")), Answers.anyWarnings(last))
        assertEquals(Phrase("answer.switch_off.safe"), Answers.switchOff(last))
        assertTrue(states.size > 40, "evaluated on every snapshot and every tick: ${states.size}")
        // Time only moved forward, which the engine insists on.
        assertEquals(states.map { it.first }.sorted(), states.map { it.first })
    }

    @Test
    fun theSimulatedAdblueLevelIsReadFromTheReductantControllerAndEvaluated() = runTest {
        val vehicle = simulation.buildVehicle()
        val transport = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val sessionConfig = VehicleSession.Config(driver = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO))
        val session = VehicleSession(transport, initScript, catalog, backgroundScope, testTimeSource, sessionConfig)
        session.start()
        advanceTimeBy(20.seconds)
        runCurrent()
        val engine = RulesEngine(config, CatalogSignalTrust(catalog))
        val state = engine.evaluate(session.telemetry.value, currentTime.milliseconds)
        assertTrue("adblueLevel" !in state.staleInputs)
        assertTrue(state.alerts.none { it.id.startsWith("def.") })
    }
}
