package knightlink.voice.intents

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How old a reading may be and still count as current in an answer: a function from signal id to maximum age. */
public object ReadingAges {
    /** 10 seconds for every signal: a few missed polls of even the slowest tier, short enough that a dropped signal is noticed. */
    public val DEFAULT: (String) -> Duration = { 10.seconds }

    /**
     * Ages derived from how often each signal is polled: `max(minimum, factor x period)`, so a signal polled every 6 s may be
     * 18 s old while a 150 ms signal still gets [minimum].
     *
     * - A signal whose period is infinite (asked once per connection) may be of any age: [Duration.INFINITE].
     * - A signal not in [periods] is not known to be polled at all, so it gets the strict [minimum].
     * - A period that is zero or negative counts as [minimum].
     *
     * @param periods poll period per signal id (e.g. from the catalog's poll tiers)
     */
    public fun fromPeriods(periods: Map<String, Duration>, minimum: Duration = 5.seconds, factor: Int = 3): (String) -> Duration = { id ->
        val period = periods[id]
        when {
            period == null -> minimum
            period.isInfinite() -> Duration.INFINITE
            else -> maxOf(minimum, period * factor)
        }
    }
}
