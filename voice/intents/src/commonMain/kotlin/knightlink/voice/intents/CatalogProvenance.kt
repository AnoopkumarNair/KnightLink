package knightlink.voice.intents

import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalCatalog

/** True for the provenances a safety answer may rely on: a published standard or confirmed on a real vehicle. Null (unknown) is not. */
public fun Provenance?.isTrusted(): Boolean = this == Provenance.STANDARD || this == Provenance.VERIFIED

private fun Provenance.trustRank(): Int = when (this) {
    Provenance.STANDARD, Provenance.VERIFIED -> 2
    Provenance.COMMUNITY -> 1
    Provenance.INFERRED -> 0
}

/**
 * Provenance of every signal the catalog decodes, looked up by (signal id, ECU): the entry for that ECU first, then the entry that
 * applies to any ECU. When the same (id, ECU) is defined more than once with different provenances the LEAST trusted one is kept
 * (STANDARD and VERIFIED rank equal, above COMMUNITY, above INFERRED). A signal the catalog does not know gives null.
 */
public fun SignalCatalog.signalProvenance(): SignalProvenance {
    val byKey = HashMap<Pair<String, String?>, Provenance>()
    for (parameter in parameters) {
        val ecu = parameter.definition.ecu
        val provenance = parameter.definition.provenance
        for (signal in parameter.signals) {
            val key = signal.definition.id to ecu
            val known = byKey[key]
            if (known == null || provenance.trustRank() < known.trustRank()) byKey[key] = provenance
        }
    }
    return SignalProvenance { signalId, ecu -> (ecu?.let { byKey[signalId to it] }) ?: byKey[signalId to null] }
}
