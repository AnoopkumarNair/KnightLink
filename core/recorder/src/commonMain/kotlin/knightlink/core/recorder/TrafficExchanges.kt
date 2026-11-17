package knightlink.core.recorder

/**
 * One command the app sent to the adapter and what came back, read out of a recording for analysis.
 *
 * @property timeMs when the command was sent, in milliseconds since the recording started
 * @property header the request identifier the adapter had selected (`7E1`) for a diagnostic request; null for an adapter command
 * @property command the command as sent, upper case without spaces, including the response-count digit when there was one
 * @property response every byte the adapter sent back, as the transport delivered them (text, with the prompt)
 */
public class TrafficExchange(
    public val timeMs: Long,
    public val header: String?,
    public val command: String,
    public val response: ByteArray,
) {
    /** The request with its response-count digit left off, so `221E121` and `221E12` are the same request. Adapter commands are unchanged. */
    public val request: String
        get() = if (header != null && command.length % 2 == 1) command.dropLast(1) else command
}

/** Every command in the recording with its answer, in order. Notes and external samples are not traffic and are left out. */
public fun Recording.trafficExchanges(): List<TrafficExchange> = indexExchanges(events).map { exchange ->
    TrafficExchange(
        timeMs = events[exchange.txIndex].timeMs,
        header = exchange.replayKey.header,
        command = exchange.key,
        response = exchange.rxIndices.map { events[it].bytes }.concat(),
    )
}
