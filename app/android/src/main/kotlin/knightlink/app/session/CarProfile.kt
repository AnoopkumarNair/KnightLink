package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.core.vehicle.UNKNOWN_MAKE
import knightlink.core.vehicle.VehicleIdentity
import java.util.Locale

/** What the driver says the car runs on. Asked once, because the car's own signals rarely say it plainly. */
enum class FuelType(val tag: String) {
    PETROL("petrol"),
    DIESEL("diesel"),
    CNG("cng"),
    HYBRID("hybrid"),
    ;

    companion object {
        fun fromTag(tag: String?): FuelType? = entries.firstOrNull { it.tag == tag }
    }
}

enum class Transmission(val tag: String) {
    MANUAL("manual"),
    AUTOMATIC("automatic"),
    ;

    companion object {
        fun fromTag(tag: String?): Transmission? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * What the driver told the app about one car, kept on this phone only. The key never holds the VIN: the make and the last four characters.
 *
 * @property nickname what the driver calls the car; null shows the make and model instead
 * @property odometerKm the dashboard odometer the driver typed, with the phone's clock in [odometerAtMs] (0 when unknown)
 */
data class CarProfile(
    val make: String,
    val model: String?,
    val nickname: String? = null,
    val fuelType: FuelType? = null,
    val transmission: Transmission? = null,
    val odometerKm: Long? = null,
    val odometerAtMs: Long = 0,
    val tankCapacityL: Double? = null,
) {
    /** The name to greet the car by: the nickname, else make and model. */
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(make.takeIf { it != UNKNOWN_MAKE }, model).joinToString(" ").ifBlank { make }
}

/** Whether the car in the socket is one the app has met before (in the garage on this phone). */
sealed interface GarageStatus {
    /** The car is not identified yet. */
    data object Unknown : GarageStatus

    /** Not in the garage: ask for make, model, fuel, transmission and odometer. */
    data object New : GarageStatus

    data class Known(val profile: CarProfile) : GarageStatus
}

/**
 * The garage: a [CarProfile] per car, in a small key-value [store] (SharedPreferences in the app). Plain strings per field, so a damaged or
 * missing field costs only itself.
 */
class CarProfileStore(private val store: StringStore) {
    /** The key of the car [identity] describes, or null when the car cannot be told from another (no end of a VIN). */
    fun keyFor(identity: VehicleIdentity?): String? {
        val tail = identity?.vinLast4?.takeIf { it.isNotBlank() } ?: return null
        return "${identity.make.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }}-${tail.lowercase(Locale.ROOT)}"
    }

    fun load(key: String): CarProfile? {
        val make = store.get(field(key, MAKE))?.takeIf { it.isNotBlank() } ?: return null
        return CarProfile(
            make = make,
            model = store.get(field(key, MODEL))?.takeIf { it.isNotBlank() },
            nickname = store.get(field(key, NAME))?.takeIf { it.isNotBlank() },
            fuelType = FuelType.fromTag(store.get(field(key, FUEL))),
            transmission = Transmission.fromTag(store.get(field(key, GEARBOX))),
            odometerKm = store.get(field(key, ODOMETER))?.toLongOrNull(),
            odometerAtMs = store.get(field(key, ODOMETER_AT))?.toLongOrNull() ?: 0,
            tankCapacityL = store.get(field(key, TANK_L))?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
        )
    }

    fun save(key: String, profile: CarProfile) {
        store.put(field(key, MAKE), profile.make.ifBlank { null })
        store.put(field(key, MODEL), profile.model?.takeIf { it.isNotBlank() })
        store.put(field(key, NAME), profile.nickname?.takeIf { it.isNotBlank() })
        store.put(field(key, FUEL), profile.fuelType?.tag)
        store.put(field(key, GEARBOX), profile.transmission?.tag)
        store.put(field(key, ODOMETER), profile.odometerKm?.toString())
        store.put(field(key, ODOMETER_AT), profile.odometerAtMs.takeIf { it > 0 }?.toString())
        store.put(field(key, TANK_L), profile.tankCapacityL?.takeIf { it.isFinite() && it > 0.0 }?.toString())
    }

    private fun field(key: String, name: String) = "car.$key.$name"

    private companion object {
        const val MAKE = "make"
        const val MODEL = "model"
        const val NAME = "name"
        const val FUEL = "fuel"
        const val GEARBOX = "gearbox"
        const val ODOMETER = "odometer"
        const val ODOMETER_AT = "odometer_at"
        const val TANK_L = "tank_l"
    }
}

/** The fuel price per litre in rupees, entered by the driver; empty by default (nothing is assumed). Kept on this phone. */
class FuelPriceStore(private val store: StringStore) {
    fun load(): Double? = parse(store.get(KEY))

    /** Saves [text] when it is a usable price and returns it; an empty text clears the price (returns null); anything else leaves it as it was. */
    fun save(text: String): Double? {
        if (text.isBlank()) {
            store.put(KEY, null)
            return null
        }
        val price = parse(text) ?: return load()
        store.put(KEY, price.toString())
        return price
    }

    companion object {
        private const val KEY = "fuel_price_per_litre"
        private const val MAX_PRICE = 1000.0

        /** Plain digits with at most one decimal point or comma: no sign, no exponent, no `NaN`, no `5f` (which Kotlin's own parser would take). */
        private val PLAIN_NUMBER = Regex("""(\d+([.,]\d*)?|[.,]\d+)""")

        /** A price above zero and at most [MAX_PRICE]; a comma works as the decimal point. Null for anything else. */
        fun parse(text: String?): Double? {
            val plain = text?.trim()?.takeIf { PLAIN_NUMBER.matches(it) } ?: return null
            return plain.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it <= MAX_PRICE && it.isFinite() }
        }
    }
}
