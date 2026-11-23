package knightlink.app.session

import knightlink.core.runtime.InMemoryProtocolMemoryStore
import knightlink.core.runtime.ProtocolMemoryStore
import knightlink.core.runtime.ProtocolRecord
import java.io.File
import java.io.IOException

/** Keeps the protocol records in one small JSON file in app-private storage. No VIN in it, only manufacturer codes. */
class FileProtocolMemoryStore(private val file: File) : ProtocolMemoryStore {
    private val inner: InMemoryProtocolMemoryStore = InMemoryProtocolMemoryStore.fromJson(
        try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (_: IOException) {
            null
        },
    )

    @Synchronized
    override fun last(adapterId: String): ProtocolRecord? = inner.last(adapterId)

    @Synchronized
    override fun lookup(adapterId: String, vinPrefix: String): ProtocolRecord? = inner.lookup(adapterId, vinPrefix)

    @Synchronized
    override fun all(): List<ProtocolRecord> = inner.all()

    @Synchronized
    override fun save(record: ProtocolRecord) {
        inner.save(record)
        try {
            file.parentFile?.mkdirs()
            file.writeText(inner.toJson(), Charsets.UTF_8)
        } catch (_: IOException) {
            // Remembering is a convenience.
        }
    }
}
