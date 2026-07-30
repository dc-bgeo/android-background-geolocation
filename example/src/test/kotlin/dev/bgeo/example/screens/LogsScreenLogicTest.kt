package dev.bgeo.example.screens

import com.bgeo.sdk.LogEntry
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsScreenLogicTest {

    private fun entry(level: Int, event: String, ts: String = "2026-07-29T10:00:00.000Z") =
        LogEntry(ts = ts, level = level, src = "native", event = event, message = null, data = null)

    // ---- nativeLogEntries: event == "app" is this platform's src filter ----

    @Test
    fun `nativeLogEntries drops LogUploader-authored app entries`() {
        val entries = listOf(entry(3, "app"), entry(1, "track.start"))
        assertEquals(listOf("track.start"), nativeLogEntries(entries).map { it.event })
    }

    @Test
    fun `nativeLogEntries keeps genuine dot-namespaced engine diagnostics untouched`() {
        val entries = listOf(entry(3, "wake.rearm"), entry(4, "motion.stop_countdown"))
        assertEquals(entries, nativeLogEntries(entries))
    }

    // ---- logLineFromEntry: numeric Transistor scale -> LogLevel ----

    @Test
    fun `logLineFromEntry maps every numeric level to its LogLevel`() {
        assertEquals(LogLevel.ERROR, logLineFromEntry(entry(1, "e")).level)
        assertEquals(LogLevel.WARN, logLineFromEntry(entry(2, "e")).level)
        assertEquals(LogLevel.INFO, logLineFromEntry(entry(3, "e")).level)
        assertEquals(LogLevel.DEBUG, logLineFromEntry(entry(4, "e")).level)
        assertEquals(LogLevel.VERBOSE, logLineFromEntry(entry(5, "e")).level)
    }

    @Test
    fun `logLineFromEntry falls back to INFO for an unrecognised level`() {
        assertEquals(LogLevel.INFO, logLineFromEntry(entry(0, "e")).level)
        assertEquals(LogLevel.INFO, logLineFromEntry(entry(99, "e")).level)
    }

    @Test
    fun `logLineFromEntry carries ts, event, message and data through unchanged`() {
        val e = LogEntry(ts = "2026-07-29T10:00:00.000Z", level = 3, src = "native", event = "track.start", message = "started", data = "raw")
        val line = logLineFromEntry(e)
        assertEquals("2026-07-29T10:00:00.000Z", line.ts)
        assertEquals("track.start", line.event)
        assertEquals("started", line.message)
        assertEquals("raw", line.data)
    }

    // ---- mergeAndFilterLogs ----

    private fun line(ts: String, level: LogLevel = LogLevel.INFO, event: String = "e") =
        LogLine(ts = ts, level = level, event = event)

    @Test
    fun `mergeAndFilterLogs sorts app and native lines together by timestamp`() {
        val appLogs = listOf(line("2026-07-29T10:00:02.000Z", event = "app2"))
        val nativeLines = listOf(line("2026-07-29T10:00:01.000Z", event = "native1"), line("2026-07-29T10:00:03.000Z", event = "native3"))

        val merged = mergeAndFilterLogs(appLogs, nativeLines, level = null)

        assertEquals(listOf("native1", "app2", "native3"), merged.map { it.event })
    }

    @Test
    fun `mergeAndFilterLogs with a null level returns every line unfiltered`() {
        val lines = listOf(line("t1", LogLevel.ERROR), line("t2", LogLevel.VERBOSE))
        assertEquals(2, mergeAndFilterLogs(lines, emptyList(), level = null).size)
    }

    @Test
    fun `mergeAndFilterLogs with a specific level keeps only matching lines`() {
        val lines = listOf(line("t1", LogLevel.ERROR, "err"), line("t2", LogLevel.INFO, "info1"), line("t3", LogLevel.INFO, "info2"))
        val filtered = mergeAndFilterLogs(lines, emptyList(), level = LogLevel.INFO)
        assertEquals(listOf("info1", "info2"), filtered.map { it.event })
    }

    // ---- logTimeSlice: bounded, never throws ----

    @Test
    fun `logTimeSlice extracts the HH-mm-ss SSS portion of a full ISO timestamp`() {
        assertEquals("10:00:00.123", logTimeSlice("2026-07-29T10:00:00.123Z"))
    }

    @Test
    fun `logTimeSlice returns the input unchanged when it is too short to slice`() {
        assertEquals("short", logTimeSlice("short"))
    }

    @Test
    fun `logTimeSlice does not throw on a string shorter than the end bound`() {
        // 15 chars: past the start (11) but short of the end bound (23).
        assertEquals("10:00:00", logTimeSlice("2026-07-29T10:00:00"))
    }
}
