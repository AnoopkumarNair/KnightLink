package knightlink.core.transport.ble

/** One GATT service as discovered on a peripheral, reduced to what profile resolution needs. */
public data class GattService(
    val uuid: String,
    val characteristics: List<GattCharacteristic>,
)

/** One GATT characteristic with the properties the peripheral declares for it. */
public data class GattCharacteristic(
    val uuid: String,
    val canWrite: Boolean,
    val canWriteWithoutResponse: Boolean,
    val canNotify: Boolean,
    val canIndicate: Boolean,
) {
    /** How to subscribe to this characteristic: notifications are preferred (no per-packet acknowledgement), indications are the fallback. */
    public val subscription: GattSubscription?
        get() =
            when {
                canNotify -> GattSubscription.NOTIFY
                canIndicate -> GattSubscription.INDICATE
                else -> null
            }

    internal fun describeProperties(): String {
        val names =
            listOfNotNull(
                "write".takeIf { canWrite },
                "write without response".takeIf { canWriteWithoutResponse },
                "notify".takeIf { canNotify },
                "indicate".takeIf { canIndicate },
            )
        return if (names.isEmpty()) "none of write, write without response, notify, indicate" else names.joinToString(", ")
    }
}

/** The two ways a peripheral can push characteristic values. */
public enum class GattSubscription { NOTIFY, INDICATE }

/** Whether a write waits for the peripheral's acknowledgement. */
public enum class GattWriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

/**
 * Transport-level description of a serial-over-GATT channel: the same facts as the adapter definition's BLE profile,
 * restated here so this module does not depend on the adapter module. Nothing in code supplies these values; they
 * always come from a definition file.
 */
public data class BleSerialProfile(
    val serviceUuid: String,
    val writeCharacteristicUuid: String,
    val notifyCharacteristicUuid: String,
    /** Services that look usable but must never be chosen, even if they would otherwise match. */
    val ignoreServiceUuids: List<String> = emptyList(),
)

/** Outcome of [GattProfileResolver.resolve]. */
public sealed interface Resolution {
    /** The serial channel was found. [service], [write] and [notify] are the instances passed to the resolver (same objects, not copies). */
    public data class Found(
        val service: GattService,
        val write: GattCharacteristic,
        val notify: GattCharacteristic,
        val writeType: GattWriteType,
    ) : Resolution

    /** The channel is not usable; [reason] says precisely why and is fit to show to a user. */
    public data class NotFound(
        val reason: String,
    ) : Resolution
}

/** Picks the serial channel out of the services a peripheral exposes. Pure, so it is tested without any Bluetooth stack. */
public object GattProfileResolver {
    /**
     * Finds the service, write characteristic and notify characteristic named by [profile] among [services].
     *
     * UUIDs are compared with [BluetoothUuid.matches]. A service listed in [BleSerialProfile.ignoreServiceUuids] is
     * never returned, even when its UUID equals the profile's. The write characteristic must support write or write
     * without response (without response wins when both exist, because ELM-style adapters are faster that way); the
     * notify characteristic must support notify or indicate.
     */
    public fun resolve(
        services: List<GattService>,
        profile: BleSerialProfile,
    ): Resolution {
        val ignored = profile.ignoreServiceUuids.map(BluetoothUuid::normalize).toSet()
        val wanted = BluetoothUuid.normalize(profile.serviceUuid)
        if (wanted in ignored) {
            return Resolution.NotFound("The profile lists service ${profile.serviceUuid} both as the serial service and as an ignored service")
        }
        // A peripheral may expose the same service UUID twice; the first instance that works wins.
        val candidates = services.filter { BluetoothUuid.normalize(it.uuid) == wanted }
        if (candidates.isEmpty()) {
            val present = services.filter { BluetoothUuid.normalize(it.uuid) !in ignored }.joinToString { it.uuid }
            return Resolution.NotFound(
                "Service ${profile.serviceUuid} is missing on the adapter" +
                    if (present.isEmpty()) " (it exposes no usable services)" else " (it exposes: $present)",
            )
        }
        var firstFailure: Resolution.NotFound? = null
        for (service in candidates) {
            when (val attempt = resolveIn(service, profile)) {
                is Resolution.Found -> return attempt
                is Resolution.NotFound -> if (firstFailure == null) firstFailure = attempt
            }
        }
        return checkNotNull(firstFailure)
    }

    private fun resolveIn(
        service: GattService,
        profile: BleSerialProfile,
    ): Resolution {
        val writeCandidates = service.characteristics.filter { BluetoothUuid.matches(it.uuid, profile.writeCharacteristicUuid) }
        if (writeCandidates.isEmpty()) return missingCharacteristic(service, profile.writeCharacteristicUuid, "write")
        val write =
            writeCandidates.firstOrNull { it.canWrite || it.canWriteWithoutResponse }
                ?: return wrongProperties(service, writeCandidates.first(), "written (it supports neither write nor write without response)")

        val notifyCandidates = service.characteristics.filter { BluetoothUuid.matches(it.uuid, profile.notifyCharacteristicUuid) }
        if (notifyCandidates.isEmpty()) return missingCharacteristic(service, profile.notifyCharacteristicUuid, "notify")
        val notify =
            notifyCandidates.firstOrNull { it.subscription != null }
                ?: return wrongProperties(service, notifyCandidates.first(), "subscribed to (it supports neither notify nor indicate)")

        // Acknowledged writes whenever the characteristic allows them: the path proven with the reference adapter (and the
        // default of the widely used Plugin.BLE library); unacknowledged writes are only a fallback.
        val writeType = if (write.canWrite) GattWriteType.WITH_RESPONSE else GattWriteType.WITHOUT_RESPONSE
        return Resolution.Found(service, write, notify, writeType)
    }

    private fun missingCharacteristic(
        service: GattService,
        uuid: String,
        role: String,
    ): Resolution.NotFound {
        val present = service.characteristics.joinToString { it.uuid }
        return Resolution.NotFound(
            "The serial service ${service.uuid} has no $role characteristic $uuid" +
                if (present.isEmpty()) " (it has no characteristics)" else " (it has: $present)",
        )
    }

    private fun wrongProperties(
        service: GattService,
        characteristic: GattCharacteristic,
        problem: String,
    ): Resolution.NotFound =
        Resolution.NotFound(
            "Characteristic ${characteristic.uuid} in service ${service.uuid} cannot be $problem; its properties: ${characteristic.describeProperties()}",
        )
}
