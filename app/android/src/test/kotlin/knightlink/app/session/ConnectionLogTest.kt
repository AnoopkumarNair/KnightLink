package knightlink.app.session

import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEvents
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionLogTest {
    private val directories = ArrayList<File>()

    // What the log must never hold: a Bluetooth address (any case, either separator) and a full VIN.
    private val address = "A1:B2:C3:D4:E5:F6"
    private val vin = "WAUZZZ8K9BA123456"

    private fun directory(): File = createTempDirectory("connection-log").toFile().also { directories += it }

    /** A clock that moves one second per reading, so files created in a row get different names. */
    private class SteppingClock : Clock() {
        private var seconds = 0L

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-17T08:00:00Z").plusSeconds(seconds++)
    }

    private fun log(directory: File, keep: Int = 5, maxFileBytes: Int = ConnectionLog.DEFAULT_MAX_FILE_BYTES) =
        ConnectionLog(directory, keep, maxFileBytes, SteppingClock())

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun ConnectionLog.allText(): String = files().joinToString("\n") { it.readText() }

    @Test
    fun anAttemptIsOneFileFromTheFirstEventUntilItIsEnded() {
        val log = log(directory())
        log.app("adapter picked")
        log.append(SessionEvent(SessionEvents.LINK_UP, "the link is open"))
        assertEquals(1, log.files().size)
        val lines = log.files().single().readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("app: adapter picked"), lines[0])
        assertTrue(lines[1].endsWith("link-up: the link is open"), lines[1])
        // A time of day first, so a line can be matched with logcat.
        assertTrue(lines[0].matches(Regex("""\d\d:\d\d:\d\d\.\d{3} {2}app: .*""")), lines[0])

        log.endAttempt()
        log.app("second attempt")
        assertEquals(2, log.files().size)
        assertEquals(1, log.files().first().readLines().size, "the newest file holds only the second attempt")
    }

    @Test
    fun theSessionsEventsArriveThroughTheSink() = runTest {
        val log = log(directory())
        log.sink.emit(SessionEvent(SessionEvents.STATE, "Probing, ADAPTER"))
        assertTrue(log.allText().contains("state: Probing, ADAPTER"))
    }

    @Test
    fun onlyTheNewestFiveAttemptsAreKept() {
        val log = log(directory(), keep = 5)
        repeat(8) {
            log.app("attempt $it")
            log.endAttempt()
        }
        val kept = log.files()
        assertEquals(5, kept.size)
        assertEquals(listOf(7, 6, 5, 4, 3).map { "attempt $it" }, kept.map { it.readText().trim().substringAfter("app: ") })
    }

    @Test
    fun aFileThatGrowsTooLargeLosesItsMiddleAndKeepsTheStartAndTheEnd() {
        val log = log(directory(), maxFileBytes = 8 * 1024)
        log.app("the start")
        repeat(1000) { log.app("filler line number $it ".padEnd(80, '.')) }
        log.app("the end")

        val file = log.files().single()
        assertTrue(file.length() <= 8 * 1024 + 200, "file is ${file.length()} bytes")
        val text = file.readText()
        assertTrue(text.contains("the start"))
        assertTrue(text.contains("the end"))
        assertTrue(text.contains("lines left out to keep the log small"), text.take(300))
        assertFalse(text.contains("filler line number 500 "), "the middle is gone")
        assertTrue(text.contains("filler line number 999 "), "the last lines before the end are kept")
    }

    @Test
    fun noAddressAndNoVinIsEverWrittenWhateverTheCallerPasses() = runTest {
        val log = log(directory())
        log.app("picked $address")
        log.app("picked ${address.lowercase()} and ${address.replace(':', '-')}")
        log.app("the VIN is $vin")
        log.append(SessionEvent(SessionEvents.RECONNECT, "could not open the link: failed to connect to $address"))
        log.sink.emit(SessionEvent(SessionEvents.PROBE_RESULT, "VIN $vin on 7E0"))

        for (text in listOf(log.allText(), log.shareable())) {
            assertFalse(address in text, text)
            assertFalse(address.lowercase() in text.lowercase(), text)
            assertFalse(address.replace(':', '-') in text, text)
            assertFalse(vin in text, text)
            assertFalse(vin.drop(3) in text, text)
        }
        assertTrue(log.allText().contains("[address removed]"))
        assertTrue(log.allText().contains("[VIN removed]"))
    }

    @Test
    fun theSharedCopyHoldsNoAddressAndNoVinEvenIfAFileWasWrittenWithoutThat() {
        val directory = directory()
        val log = log(directory)
        // As an older build, or someone, might have left it.
        File(directory, "connection-20260101T000000.000Z.log").writeText("08:00:00.000  app: picked $address, car $vin\n")

        val copy = assertNotNull(log.prepareShare(File(directory, "shared")))

        val text = copy.readText()
        assertFalse(address in text)
        assertFalse(vin in text)
        assertTrue(text.contains("picked [address removed], car [VIN removed]"), text)
    }

    @Test
    fun theSharedCopyHoldsAllKeptAttemptsOldestFirstAndReplacesEarlierCopies() {
        val directory = directory()
        val log = log(directory)
        log.app("first attempt")
        log.endAttempt()
        log.app("second attempt")
        val out = File(directory, "shared")

        val first = assertNotNull(log.prepareShare(out))
        log.app("more")
        val second = assertNotNull(log.prepareShare(out))

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(listOf(ConnectionLog.SHARED_NAME), out.list().orEmpty().toList(), "only the latest copy stays")
        val text = second.readText()
        assertTrue(text.indexOf("first attempt") < text.indexOf("second attempt"))
        assertTrue(text.contains("more"))
        assertTrue(text.contains("2 attempts"))
    }

    @Test
    fun anEmptyLogSaysSoInsteadOfSharingNothing() {
        val text = log(directory()).shareable()
        assertTrue(text.contains("nothing has been recorded yet"))
    }

    @Test
    fun anEventIsAlwaysOneLine() {
        val log = log(directory())
        log.app("line one\nline two\r\nline three")
        assertEquals(1, log.files().single().readLines().size)
    }

    @Test
    fun aLogThatCannotBeWrittenNeverThrows() {
        val blocker = File.createTempFile("not-a-directory", ".tmp").also { directories += it }
        val log = ConnectionLog(File(blocker, "inside"))
        log.app("this goes nowhere")
        log.append(SessionEvent(SessionEvents.END))
        assertTrue(log.files().isEmpty())
        assertEquals(null, log.prepareShare(File(blocker, "shared")))
    }

    @Test
    fun theActivityAndTheServiceShareOneLogPerDirectory() {
        val directory = directory()
        assertSame(ConnectionLog.of(directory), ConnectionLog.of(File(directory.absolutePath)))
        assertNotSame(ConnectionLog.of(directory), ConnectionLog.of(directory()))
    }
}
