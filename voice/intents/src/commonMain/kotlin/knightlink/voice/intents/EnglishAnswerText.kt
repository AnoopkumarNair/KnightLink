package knightlink.voice.intents

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * English wording of every [AnswerKeys] key. A template holds `{name}` placeholders filled from the answer's args: a quantity is
 * spoken with its unit ("42 degrees", "17 percent", "12.6 volts"), a nested phrase is rendered and loses its closing full stop, a
 * list of phrases is joined "a, b and c". The first letter of the finished sentence is capitalised.
 */
public object EnglishAnswerText : AnswerText {
    internal val templates: Map<String, String> = mapOf(
        AnswerKeys.HELP to
            "I can answer questions about the DPF, temperatures, fuel and AdBlue, battery, speed and revs, warnings, and whether you can switch off.",
        AnswerKeys.REPEAT_NOTHING to "I haven't said anything yet.",
        AnswerKeys.LINK_NOT_CONNECTED to "I'm not connected to the car.",
        AnswerKeys.LINK_CONNECTING to "I'm still connecting to the car.",
        AnswerKeys.LINK_WAITING_FOR_IGNITION to "The adapter is connected but the car isn't answering. Is the ignition on?",
        AnswerKeys.LINK_RECONNECTING to "I lost the connection to the car and I'm reconnecting.",
        AnswerKeys.VALUE_UNKNOWN to "I don't have a current {topic} yet.",
        AnswerKeys.TOPIC_COOLANT to "coolant temperature",
        AnswerKeys.TOPIC_INTAKE to "intake air temperature",
        AnswerKeys.TOPIC_EXHAUST to "exhaust temperature",
        AnswerKeys.TOPIC_TEMPERATURES to "temperature reading",
        AnswerKeys.TOPIC_FUEL to "fuel level",
        AnswerKeys.TOPIC_ADBLUE to "AdBlue level",
        AnswerKeys.TOPIC_BATTERY to "battery voltage",
        AnswerKeys.TOPIC_SPEED to "speed",
        AnswerKeys.TOPIC_RPM to "engine speed",
        AnswerKeys.TOPIC_DPF to "DPF reading",
        AnswerKeys.COOLANT_WARMING_UP to "The coolant is at {value}, still warming up.",
        AnswerKeys.COOLANT_NORMAL to "The coolant is at {value}, which is normal.",
        AnswerKeys.COOLANT_HOT to "The coolant is at {value}, running hot.",
        AnswerKeys.INTAKE_VALUE to "The intake air is at {value}.",
        AnswerKeys.EXHAUST_VALUE to "The exhaust is at {value}.",
        AnswerKeys.TEMPERATURES_LIST to "{parts}.",
        AnswerKeys.TEMPERATURE_PART_COOLANT to "coolant {value}",
        AnswerKeys.TEMPERATURE_PART_INTAKE to "intake air {value}",
        AnswerKeys.TEMPERATURE_PART_EXHAUST to "exhaust {value}",
        AnswerKeys.FUEL_LEVEL to "The fuel level is {value}.",
        AnswerKeys.FUEL_LOW to "The fuel level is {value}, low, refuel soon.",
        AnswerKeys.ADBLUE_LEVEL to "The AdBlue level is {value}.",
        AnswerKeys.ADBLUE_LOW to "The AdBlue level is {value}, low, top it up soon.",
        AnswerKeys.ADBLUE_UNCONFIRMED to "The AdBlue reading is unconfirmed, but it suggests about {value}.",
        AnswerKeys.ADBLUE_UNCONFIRMED_LOW to "The AdBlue reading is unconfirmed, but it suggests about {value}, which would be low.",
        AnswerKeys.BATTERY_VOLTAGE to "The battery is at {value}.",
        AnswerKeys.BATTERY_CHARGING_NORMALLY to "The battery is at {value}, charging normally.",
        AnswerKeys.BATTERY_NOT_CHARGING to "The battery is at {value}, not charging properly.",
        AnswerKeys.BATTERY_TOO_HIGH to "The battery is at {value}, which is too high.",
        AnswerKeys.BATTERY_HEALTHY to "The battery is at {value}, healthy.",
        AnswerKeys.BATTERY_A_BIT_LOW to "The battery is at {value}, a bit low.",
        AnswerKeys.BATTERY_LOW to "The battery is at {value}, low.",
        AnswerKeys.SPEED_VALUE to "You're doing {value}.",
        AnswerKeys.SPEED_STOPPED to "You're not moving.",
        AnswerKeys.ENGINE_OFF to "The engine is off.",
        AnswerKeys.RPM_VALUE to "The engine is at {value}.",
        AnswerKeys.DPF_REGENERATING to "The DPF is regenerating, soot at {soot}. Keep driving if you can until it finishes.",
        AnswerKeys.DPF_IDLE to "Soot load is {soot}, no regeneration running.",
        AnswerKeys.DPF_IDLE_DUE to "Soot load is {soot}, no regeneration running, but a regeneration is due soon.",
        AnswerKeys.DPF_SOOT_ONLY to "Soot load is {soot}, but I can't tell whether it is regenerating.",
        AnswerKeys.DPF_REGEN_ONLY_ACTIVE to "The DPF is regenerating. Keep driving if you can until it finishes.",
        AnswerKeys.DPF_REGEN_ONLY_IDLE to "No regeneration is running, but I don't have a current soot reading.",
        AnswerKeys.WARNINGS_ONE to "There is a warning: {message}.",
        AnswerKeys.WARNINGS_MANY to "There are {count} warnings. The most important: {message}.",
        AnswerKeys.WARNINGS_AND_MIL to "{warnings}. Also, {mil}.",
        AnswerKeys.WARNINGS_NONE to "No warnings, and the engine warning light is off.",
        AnswerKeys.WARNINGS_NONE_MIL_ON to "There are no active warnings, but {mil}.",
        AnswerKeys.WARNINGS_NONE_MIL_UNKNOWN to "No warnings, but I can't read the engine warning light right now.",
        AnswerKeys.WARNINGS_UNMONITORED_MIL_ON to "The warning checks aren't running yet, and {mil}.",
        AnswerKeys.WARNINGS_UNMONITORED_MIL_OFF to "The warning checks aren't running yet, but the engine warning light is off.",
        AnswerKeys.WARNINGS_UNMONITORED_MIL_UNKNOWN to "The warning checks aren't running yet, and I can't read the engine warning light right now.",
        AnswerKeys.MIL_ON to "the check engine light is on, with {count} fault codes stored",
        AnswerKeys.MIL_ON_ONE_CODE to "the check engine light is on, with one fault code stored",
        AnswerKeys.MIL_ON_NO_COUNT to "the check engine light is on",
        AnswerKeys.SWITCH_OFF_YES to "Yes, it's fine to switch off.",
        AnswerKeys.SWITCH_OFF_YES_UNMONITORED to "No regeneration is running, so it's fine to switch off.",
        AnswerKeys.SWITCH_OFF_BLOCKED_BY_WARNING to "Not yet: {warning}.",
        AnswerKeys.SWITCH_OFF_MOVING to "You're still moving.",
        AnswerKeys.SWITCH_OFF_UNKNOWN to "I can't confirm: I don't have a current DPF reading.",
        AnswerKeys.SWITCH_OFF_REGENERATING to "Not yet: the DPF is regenerating (soot {soot}). Keep the engine running until it finishes.",
        AnswerKeys.SWITCH_OFF_REGENERATING_NO_SOOT to "Not yet: the DPF is regenerating. Keep the engine running until it finishes.",
        AnswerKeys.SWITCH_OFF_EXHAUST_HOT to "Let it idle for a minute first: the exhaust is at {value}.",
        AnswerKeys.SWITCH_OFF_SPEED_UNKNOWN to "No regeneration is running, but I can't see the speed: switch off once you've stopped.",
        AnswerKeys.STATUS_SUMMARY to "I'm connected, and {parts}.",
        AnswerKeys.STATUS_PART_NO_WARNINGS to "there are no warnings",
        AnswerKeys.STATUS_PART_WARNING_ONE to "there is one active warning",
        AnswerKeys.STATUS_PART_WARNINGS_MANY to "there are {count} active warnings",
        AnswerKeys.STATUS_PART_MIL_ON to "the check engine light is on",
        AnswerKeys.STATUS_PART_UNMONITORED to "the warning checks aren't running yet",
        AnswerKeys.STATUS_PART_DPF_REGENERATING to "the DPF is regenerating",
        AnswerKeys.STATUS_PART_COOLANT_WARMING_UP to "the engine is still warming up",
        AnswerKeys.STATUS_PART_COOLANT_NORMAL to "the engine temperature is normal",
        AnswerKeys.STATUS_PART_COOLANT_HOT to "the engine is running hot",
        AnswerKeys.STATUS_PART_FUEL_LOW to "the fuel is low",
        AnswerKeys.STATUS_PART_NO_READINGS to "I have no current readings",
    )

    /** @throws IllegalArgumentException for a key without a template, or a template whose argument is missing */
    override fun render(answer: Answer): String = renderPhrase(answer).replaceFirstChar { it.uppercaseChar() }

    private fun renderPhrase(answer: Answer): String {
        val template = templates[answer.key] ?: throw IllegalArgumentException("No English text for answer key '${answer.key}'")
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '{') {
                out.append(c)
                i++
                continue
            }
            val end = template.indexOf('}', i)
            require(end > i) { "Unclosed placeholder in the template of '${answer.key}'" }
            val name = template.substring(i + 1, end)
            val arg = answer.args[name] ?: throw IllegalArgumentException("Answer '${answer.key}' has no argument '$name'")
            out.append(renderArg(arg))
            i = end + 1
        }
        return out.toString()
    }

    private fun renderArg(arg: AnswerArg): String = when (arg) {
        is AnswerArg.Quantity -> formatQuantity(arg)
        is AnswerArg.Count -> arg.value.toString()
        is AnswerArg.Phrase -> renderPhrase(arg.answer).removeSuffix(".")
        is AnswerArg.Phrases -> joinList(arg.answers.map { renderPhrase(it).removeSuffix(".") })
    }

    private fun joinList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun formatQuantity(q: AnswerArg.Quantity): String = when (q.unit) {
        "degC" -> counted(q.value.roundToLong(), "degree", "degrees")
        "percent" -> "${signed(q.value.roundToLong())} percent"
        "V" -> "${formatOneDecimal(q.value)} volts"
        "km/h" -> counted(q.value.roundToLong(), "kilometre per hour", "kilometres per hour")
        "rpm" -> "${signed((q.value / 10).roundToLong() * 10)} rpm"
        else -> "${formatOneDecimal(q.value)} ${q.unit}"
    }

    private fun signed(n: Long): String = if (n < 0) "minus ${-n}" else n.toString()

    private fun counted(n: Long, singular: String, plural: String): String = "${signed(n)} ${if (abs(n) == 1L) singular else plural}"

    /** Common code has no String.format: 12.64 -> "12.6", -0.04 -> "0.0". */
    private fun formatOneDecimal(value: Double): String {
        val tenths = (abs(value) * 10).roundToLong()
        val sign = if (value < 0 && tenths != 0L) "minus " else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }
}
