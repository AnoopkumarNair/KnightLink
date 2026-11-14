package knightlink.app

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog

/**
 * Proof that the shared core runs on the device: one adapter line goes through the real protocol decoder and the
 * real signal catalog. The definition is a small inline copy of the standard engine-speed parameter (SAE J1979).
 */
object SharedCoreDemo {
    const val ADAPTER_LINE = "7E8 04 41 0C 0C 7C"

    private const val DEFINITION = """
        {
          "schemaVersion": 1,
          "id": "demo/engine-speed",
          "kind": "standard",
          "title": "Engine speed",
          "parameters": [
            { "service": "01", "pid": "0C", "provenance": "standard", "signals": [
              { "id": "engine.rpm", "formula": "(256*A+B)/4", "unit": "rpm", "min": 0, "max": 16383.75 } ] }
          ]
        }
    """

    /** Decodes [line] and formats every signal it carries, e.g. `engine.rpm = 799.0 rpm`; null when it carries none. */
    fun decode(line: String = ADAPTER_LINE): String? {
        val catalog = SignalCatalog.of(listOf(DefinitionJson.parse(DEFINITION)))
        val response = ElmResponseDecoder(extendedIds = false).decode(listOf(line))
        val message = response.messages.singleOrNull() as? DiagnosticResponse.Positive ?: return null
        if (message.data.isEmpty()) return null
        val pid = "%02X".format(message.data[0].toInt() and 0xFF)
        val parameter = catalog.find(message.service, pid) ?: return null
        val signals = parameter.decode(message.data.copyOfRange(1, message.data.size))
        if (signals.isEmpty()) return null
        return signals.joinToString("\n") { "${it.id} = ${it.value} ${it.unit}" }
    }
}
