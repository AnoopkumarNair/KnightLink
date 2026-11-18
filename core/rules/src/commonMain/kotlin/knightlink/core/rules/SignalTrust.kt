package knightlink.core.rules

import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalCatalog

/**
 * How far a signal may be trusted for a safety decision. Only [Provenance.STANDARD] and [Provenance.VERIFIED] signals ever raise an alert
 * or feed an answer; a guess by the discovery engine must not make the car speak.
 */
public fun interface SignalTrust {
    /** @return the provenance of the definition behind [signalId] (from control unit [ecu], or any when null), or null when there is none */
    public fun provenance(signalId: String, ecu: String?): Provenance?
}

internal fun Provenance?.isTrusted(): Boolean = this == Provenance.STANDARD || this == Provenance.VERIFIED

/** Reads provenance off a [SignalCatalog]. An ECU-specific definition wins over a generic one, as in [SignalCatalog.find]. */
public class CatalogSignalTrust(catalog: SignalCatalog) : SignalTrust {
    private data class Key(val signalId: String, val ecu: String?)

    private val index: Map<Key, Provenance> = buildMap {
        for (parameter in catalog.parameters) {
            for (signal in parameter.signals) put(Key(signal.definition.id, parameter.definition.ecu), parameter.definition.provenance)
        }
    }

    override fun provenance(signalId: String, ecu: String?): Provenance? = (if (ecu != null) index[Key(signalId, ecu)] else null) ?: index[Key(signalId, null)]
}
