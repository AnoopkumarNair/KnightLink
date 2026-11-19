package knightlink.app.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * The phone's motion sensors and, when the user has allowed it, its GPS, as lines for the recording (see [MotionBatcher] and
 * [gpsLineOf]): linear acceleration (the plain accelerometer, gravity included, when the phone has no linear one) and rotation at about
 * 25 Hz, delivered in batches of up to half a second so the processor can sleep between them; position, speed, bearing and accuracy once a
 * second. Everything is read on a thread of its own, never the main one.
 *
 * Without the location permission only motion is recorded. The permission is checked when [start] is called: allowing it during a
 * session takes effect with the next one.
 */
class AndroidSampleSource(private val context: Context) : SampleSource {
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var sensors: SensorManager? = null
    private var locations: LocationManager? = null
    private var batcher = newBatcher()
    private var emit: ((String) -> Unit)? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sink = emit ?: return
            val line = synchronized(lock) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        batcher.rotation(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                        null
                    }
                    else -> batcher.acceleration(
                        timeMs = event.timestamp / NANOS_PER_MILLI,
                        x = event.values[0].toDouble(),
                        y = event.values[1].toDouble(),
                        z = event.values[2].toDouble(),
                        nowMs = SystemClock.elapsedRealtimeNanos() / NANOS_PER_MILLI,
                    )
                }
            }
            line?.let(sink)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val sink = emit ?: return
            sink(
                gpsLineOf(
                    timeMs = SystemClock.elapsedRealtime(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMetresPerSecond = location.speed.takeIf { location.hasSpeed() },
                    bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                    accuracyMetres = location.accuracy.takeIf { location.hasAccuracy() },
                ),
            )
        }

        @Deprecated("Not called on Android 10 and later", ReplaceWith(""))
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission") // checked just above
    override fun start(emit: (String) -> Unit) {
        stop()
        val manager = context.getSystemService(SensorManager::class.java)
        val handlerThread = HandlerThread("knightlink-sensors").also { it.start() }
        thread = handlerThread
        this.emit = emit
        synchronized(lock) { batcher = newBatcher() }
        sensors = manager

        val linear = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val acceleration = linear ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotation = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val handler = android.os.Handler(handlerThread.looper)
        // Batched delivery: the hardware FIFO collects readings and hands them over together, which saves the processor waking 25 times a second.
        acceleration?.let { manager?.registerListener(sensorListener, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, handler) }
        rotation?.let { manager?.registerListener(sensorListener, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, handler) }

        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var gps = if (allowed) "on" else "not allowed"
        if (allowed) {
            val location = context.getSystemService(LocationManager::class.java)
            locations = location
            try {
                location?.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, locationListener, handlerThread.looper)
            } catch (e: SecurityException) {
                gps = "refused"
            } catch (e: IllegalArgumentException) {
                gps = "no GPS on this phone"
            }
        }
        emit(
            "sensors accel=${if (linear != null) {
                "linear"
            } else if (acceleration != null) {
                "plain"
            } else {
                "none"
            }} gyro=${if (rotation != null) "yes" else "none"} gps=${gps.replace(' ', '-')}",
        )
        Log.i(TAG, "Phone sensors started (acceleration: ${acceleration != null}, rotation: ${rotation != null}, location: $gps)")
    }

    override fun stop() {
        val sink = emit
        sensors?.unregisterListener(sensorListener)
        locations?.removeUpdates(locationListener)
        sensors = null
        locations = null
        // What was still held goes into the recording: the end of a drive is not lost.
        val rest = synchronized(lock) { batcher.flush(SystemClock.elapsedRealtimeNanos() / NANOS_PER_MILLI) }
        if (rest != null) sink?.invoke(rest)
        emit = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val TAG = "PhoneSamples"
        const val NANOS_PER_MILLI = 1_000_000L

        /** What recordings keep: 10 samples a second (the phone may deliver several times that), see [MotionBatcher]. */
        fun newBatcher() = MotionBatcher(
            maxAgeMs = MotionBatcher.RECORDING_MAX_AGE_MS,
            binMs = MotionBatcher.RECORDING_BIN_MS,
        )

        /** 25 readings a second. */
        const val SAMPLING_PERIOD_US = 40_000

        /** The longest a reading may wait in the sensor hub before it is delivered. */
        const val MAX_REPORT_LATENCY_US = 500_000
        const val LOCATION_INTERVAL_MS = 1000L
    }
}
