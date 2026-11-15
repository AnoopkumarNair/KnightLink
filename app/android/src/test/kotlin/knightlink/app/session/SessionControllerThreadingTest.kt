package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.InMemoryRecordSink
import knightlink.core.recorder.RecordSink
import knightlink.core.sim.SimulationFile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session host on a real multi-threaded dispatcher and real time, as in the service. The virtual-time tests run on one
 * thread and so cannot show a race between the session's commands and the scenario player changing the simulated vehicle; this
 * one has the player change the vehicle every 10 ms while the session polls it.
 */
class SessionControllerThreadingTest {
    private val assets = RepoAssetSource()
    private val store = DefinitionStore(assets)
    private val config = AppConfig.load(assets)

    private class MemorySinks : RecordSinkFactory {
        override fun newSink(): RecordSink = InMemoryRecordSink()

        override fun finishCurrent() = Unit
    }

    @Test
    fun runsOnRealThreadsWhileTheScenarioChangesTheVehicle() = runBlocking {
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures += e })
        val base = store.simulation(config.demoScenario)
        val ecu = base.ecus.first { "010C" in it.data }.id
        // Engine speed takes a new value every 10 ms for 10 s.
        val busy = base.copy(timeline = (1..1000).map { SimulationFile.Step(atMs = it * 10L, ecu = ecu, set = mapOf("010C" to "%04X".format(it))) })
        val controller = SessionController(scope, { config }, store, DemoTransportFactory({ busy }), MemorySinks())

        try {
            controller.connect(ConnectionTarget.Demo(config.demoScenario))
            withTimeout(LIVE_TIMEOUT_MS) { controller.uiState.first { it.connection == ConnectionStatus.Live } }

            val seen = HashSet<String>()
            repeat(40) {
                controller.uiState.value.liveValues.firstOrNull { it.signalId == "engine.rpm" }?.let { seen += it.valueText }
                delay(100)
            }
            assertTrue(seen.size > 1, "engine speed never changed: $seen")

            controller.disconnect()
            assertEquals(UiState.IDLE, controller.uiState.value)
            assertTrue(failures.isEmpty(), "unexpected failures: $failures")
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val LIVE_TIMEOUT_MS = 30_000L
    }
}
