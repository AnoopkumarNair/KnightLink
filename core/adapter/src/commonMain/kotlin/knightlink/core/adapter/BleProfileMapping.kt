package knightlink.core.adapter

import knightlink.core.transport.ble.BleSerialProfile

/** The transport-level view of this adapter's BLE profile, for a BLE transport that knows nothing of adapter definitions. */
public fun AdapterDefinition.BleProfile.toSerialProfile(): BleSerialProfile =
    BleSerialProfile(
        serviceUuid = serviceUuid,
        writeCharacteristicUuid = writeCharacteristicUuid,
        notifyCharacteristicUuid = notifyCharacteristicUuid,
        ignoreServiceUuids = ignoreServiceUuids,
    )
