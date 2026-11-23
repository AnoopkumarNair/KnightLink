package knightlink.tools.cli

import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import knightlink.core.recorder.RecordingHeader
import knightlink.core.runtime.drivetruth.DriveTruthConfig
import knightlink.core.runtime.drivetruth.FuelType
import kotlin.test.Test
import kotlin.test.assertTrue

class DriveTruthCommandsTest {
    private fun tx(t: Long, text: String) = RecordingEvent.tx(t, text.encodeToByteArray())

    private fun rx(t: Long, text: String) = RecordingEvent.rx(t, text.encodeToByteArray())

    /** A minute of a made-up diesel drive: 36 km/h, 2000 rpm, 1.4 g/s of fuel (PID 9D), GPS moving at 10 m/s north. */
    private fun minuteAt36(): Recording {
        val events = ArrayList<RecordingEvent>()
        events += RecordingEvent.open(0)
        events += tx(10, "ATSH7E0\r")
        events += rx(20, "OK\r\r>")
        for (i in 0..60) {
            val t = 100L + i * 1000
            events += tx(t, "010C0D\r")
            events += rx(t + 30, "7E806410C1F400D24\r\r>")
            events += tx(t + 40, "019D\r")
            events += rx(t + 70, "7E80641 9D00460000\r\r>".replace(" ", ""))
            events += RecordingEvent.external(t + 80, "gps %.5f 77.00000 36.0 0 5".format(12.0 + i * 0.00009))
        }
        events += RecordingEvent.close(70_000)
        return Recording(RecordingHeader("adapters/x", "a minute"), events)
    }

    @Test
    fun aRecordingFeedsTheReducerThroughTheRealDecoder() {
        val lines = renderDriveTruth(minuteAt36(), catalogFor(DEFAULT_VEHICLE), DriveTruthConfig(FuelType.DIESEL, pricePerLitre = 100.0, tankLitres = 50.0))
        val text = lines.joinToString("\n")
        // 36 km/h for 60 s is 0.6 km by the car and about 0.6 km by GPS (60 * 0.00009 degrees of latitude).
        assertTrue("Car distance (PID 0D integrated): 0.60 km" in text, text)
        assertTrue("GPS distance:                      0.60 km" in text, text)
        assertTrue("via FUEL_RATE_GPS" in text, text)
        assertTrue("FUEL_DENSITY_ASSUMED" in text, text)
        assertTrue("Car vs GPS: not available" in text, text) // under the 3 km minimum to compare
    }
}
