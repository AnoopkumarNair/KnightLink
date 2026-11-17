package knightlink.core.adapter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/** Describes one adapter model: how to reach it and how to bring it up. Loaded from /definitions/adapters. */
@Serializable
public data class AdapterDefinition(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val chipset: String,
    val firmware: String? = null,
    val ble: BleProfile? = null,
    /** Commands sent in order after connecting. */
    val initScript: List<String>,
    /**
     * Commands sent after [initScript] when the capability probe is about to run, for example a longer adapter timeout for
     * the first contact with a car that is slow to wake up. Optional.
     */
    val probeScript: List<String> = emptyList(),
    /**
     * Commands sent after the probe and before polling starts, for example the adapter timeout that suits polling. Optional.
     * A re-initialisation while polling runs [initScript] and then this.
     */
    val pollScript: List<String> = emptyList(),
    /**
     * Command prefixes (no spaces, upper case) that must be accepted: an adapter that answers `?` to one of them cannot be used.
     * Any other command the adapter does not know is skipped and reported as unsupported.
     */
    val essentialCommands: List<String> = DEFAULT_ESSENTIAL_COMMANDS,
    /** Pauses and time limits that suit this adapter. Every field is optional. */
    val timing: Timing = Timing(),
    val notes: String? = null,
) {
    init {
        // A definition file is data from outside the code; it must not be able to slip a write past the driver's gate either.
        for (command in initScript + probeScript + pollScript) {
            ReadOnlyPolicy.violation(command)?.let { throw IllegalArgumentException("$id: $it") }
        }
    }

    @Serializable
    public data class BleProfile(
        /** Advertised device names that identify this adapter. */
        val names: List<String> = emptyList(),
        val serviceUuid: String,
        val writeCharacteristicUuid: String,
        val notifyCharacteristicUuid: String,
        /** Services that look usable but are not the serial channel. */
        val ignoreServiceUuids: List<String> = emptyList(),
    )

    /** Milliseconds, so the file stays plain JSON. The defaults are those of [Elm327Driver.Config]. */
    @Serializable
    public data class Timing(
        /** Send two echo-off commands before the init script, and ignore the replies. */
        val warmUp: Boolean = true,
        val warmUpDelayMs: Long = DEFAULT_WARM_UP_DELAY_MS,
        /** Pause after the reply to the reset command (ATZ). */
        val postResetDelayMs: Long = DEFAULT_POST_RESET_DELAY_MS,
        /** Pause before a command that was answered with STOPPED is sent once more. */
        val stoppedRetryDelayMs: Long = DEFAULT_STOPPED_RETRY_DELAY_MS,
        val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        val resetTimeoutMs: Long = DEFAULT_RESET_TIMEOUT_MS,
        /** Time limit of an exchange once the adapter has said it is searching for the protocol. */
        val searchingTimeoutMs: Long = DEFAULT_SEARCHING_TIMEOUT_MS,
    )

    /** The driver configuration this definition asks for. */
    public fun driverConfig(): Elm327Driver.Config = Elm327Driver.Config(
        commandTimeout = timing.commandTimeoutMs.milliseconds,
        resetTimeout = timing.resetTimeoutMs.milliseconds,
        searchingTimeout = timing.searchingTimeoutMs.milliseconds,
        warmUp = timing.warmUp,
        warmUpDelay = timing.warmUpDelayMs.milliseconds,
        postResetDelay = timing.postResetDelayMs.milliseconds,
        stoppedRetryDelay = timing.stoppedRetryDelayMs.milliseconds,
        essentialCommands = essentialCommands,
    )

    public companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** The settings every ELM327 init script needs: no echo, headers on, a protocol. */
        public val DEFAULT_ESSENTIAL_COMMANDS: List<String> = listOf("ATE0", "ATH1", "ATSP")

        private const val DEFAULT_WARM_UP_DELAY_MS = 300L
        private const val DEFAULT_POST_RESET_DELAY_MS = 500L
        private const val DEFAULT_STOPPED_RETRY_DELAY_MS = 200L
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 3000L
        private const val DEFAULT_RESET_TIMEOUT_MS = 6000L
        private const val DEFAULT_SEARCHING_TIMEOUT_MS = 8000L

        public fun parse(text: String): AdapterDefinition = json.decodeFromString(serializer(), text)
    }
}
