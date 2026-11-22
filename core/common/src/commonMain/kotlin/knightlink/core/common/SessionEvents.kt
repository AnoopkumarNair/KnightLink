package knightlink.core.common

/**
 * Something worth remembering about a connection: a step of the init script, a state change, a reset of the adapter, a
 * reconnect. Events are what lets a failed car session be read afterwards ("it stopped here, for this reason").
 *
 * Events never carry a full VIN or a Bluetooth address; [Redaction] is applied by every sink that stores text, as a second
 * line of defence, but producers must not put either in [detail] in the first place.
 *
 * @property name a short lower-case tag with dashes, one of [SessionEvents]; what a reader filters on
 * @property detail free text for a person; may be empty
 */
public data class SessionEvent(val name: String, val detail: String = "") {
    /** One line of text: the name, then the detail when there is one. */
    override fun toString(): String = if (detail.isEmpty()) name else "$name: $detail"
}

/** The tags used in [SessionEvent.name]. Strings, not an enum, so that a recording can hold tags from a newer version. */
public object SessionEvents {
    /** A new attempt to open the link starts. */
    public const val CONNECT_ATTEMPT: String = "connect-attempt"

    /** The link is open. */
    public const val LINK_UP: String = "link-up"

    /** The adapter init script starts. The detail says which script. */
    public const val INIT_START: String = "init-start"

    /** One command of an init script was answered. The detail has the command and the first line of the reply. */
    public const val INIT_STEP: String = "init-step"

    /** The init script is done. The detail lists the commands the adapter did not know, when there are any. */
    public const val INIT_END: String = "init-end"

    /** The capability probe is done. The detail is a summary without VIN. */
    public const val PROBE_RESULT: String = "probe-result"

    /** The probe missed the primary ECU or came with warnings; it is asked again. The detail says which attempt. */
    public const val PROBE_INCOMPLETE: String = "probe-incomplete"

    /** A refuel was found in the fuel level. The detail has the levels before and after. */
    public const val FUEL_FILL: String = "fuel-fill"

    /** The state of the session changed. */
    public const val STATE: String = "state"

    /** The adapter reset itself (voltage dip) or was found to have. The detail says how it was noticed. */
    public const val ADAPTER_RESET: String = "adapter-reset"

    /** The adapter is being initialised again on the same link. The detail says why. */
    public const val REINIT: String = "re-init"

    /** The link is being closed and opened again. The detail says why. */
    public const val RECONNECT: String = "reconnect"

    /** Nothing answered for the silence window. */
    public const val SILENCE: String = "silence"

    /** The adapter reported that it could not reach the vehicle bus (CAN ERROR, BUS ERROR, UNABLE TO CONNECT). */
    public const val BUS_FAULT: String = "bus-fault"

    /** The adapter's buffer overflowed. */
    public const val BUFFER_FULL: String = "buffer-full"

    /** The adapter was still searching for the protocol, so the wait was extended. */
    public const val SEARCHING: String = "searching"

    /** The adapter asked for a command twice (it said STOPPED) and it was sent again. */
    public const val RESEND: String = "resend"

    /** The vehicle answered NO DATA everywhere for a few seconds (ignition off), polling paused; and the wake-up that ended it. */
    public const val ASLEEP: String = "asleep"

    /** A reply took longer than the slow-reply limit (2 s). */
    public const val SLOW_REPLY: String = "slow-reply"

    /** What was remembered about the vehicle between sessions: reused, restored, saved (no VIN, no key). */
    public const val MEMORY: String = "memory"

    /** A request was answered "response pending" (7F xx 78) and the driver kept reading for the ECU's final answer (or gave up). */
    public const val RESPONSE_PENDING: String = "response-pending"

    /** How fast polling is going: requests per second and the update rate of every signal over the last 30 seconds. Written every 30 seconds. */
    public const val RATES: String = "rates"

    /**
     * What the full scan is doing: a stage starts or ends, the car moved and it paused, the battery voltage, why it stopped. One line for a
     * person; never a full VIN.
     */
    public const val SCAN: String = "scan"

    /** The full scan is over (finished, cancelled or stopped by a fault). The detail is the result as one line of JSON, with the VIN masked. */
    public const val SCAN_RESULT: String = "scan-result"

    /** The session ended. The detail says why. */
    public const val END: String = "end"

    /** The app did something (picker tap, service start, foreground start). Written by the app, not by the session. */
    public const val APP: String = "app"
}

/** Where [SessionEvent]s go. Implementations must not throw for ordinary failures (a full disk): losing an event must never end a session. */
public fun interface SessionEventSink {
    public suspend fun emit(event: SessionEvent)

    public companion object {
        /** Drops everything. */
        public val None: SessionEventSink = SessionEventSink { }
    }
}

/** A sink that passes every event to both [this] and [other], in that order. */
public operator fun SessionEventSink.plus(other: SessionEventSink): SessionEventSink = SessionEventSink { event ->
    emit(event)
    other.emit(event)
}

/** Shorthand for `emit(SessionEvent(name, detail))`. */
public suspend fun SessionEventSink.emit(name: String, detail: String = "") {
    emit(SessionEvent(name, detail))
}

/**
 * Removes what must never reach a log, a recording or a shared file: Bluetooth addresses and anything shaped like a VIN.
 * Deliberately blunt: it also removes a few harmless tokens, which is the right way round for a safety net.
 */
public object Redaction {
    public const val ADDRESS_PLACEHOLDER: String = "[address removed]"
    public const val VIN_PLACEHOLDER: String = "[VIN removed]"

    private val address = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val vinShaped = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""", RegexOption.IGNORE_CASE)

    public fun redact(text: String): String =
        text.replace(address, ADDRESS_PLACEHOLDER)
            .replace(vinShaped) { match -> if (match.value.any { it.isDigit() }) VIN_PLACEHOLDER else match.value }
}
