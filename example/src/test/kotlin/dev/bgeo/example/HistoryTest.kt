package dev.bgeo.example

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class HistoryTest {

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    // Fix round 1 (F7 review): same convention as `CoordinatesSheetLogicTest`
    // — pin the JVM default Locale/TimeZone away from US/UTC. On its own
    // this does NOT make `filterPointsByRange`'s relative
    // inclusion/exclusion assertions below bite a dropped `parseIsoMillis`
    // UTC pin (the same offset applied to every point AND every from/to
    // bound cancels out of any relative comparison) — see the dedicated
    // `parseIsoMillis` test below, which calls the parser directly against
    // an independent reference instead.
    @Before
    fun pinLocaleAndTimeZone() {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    private fun point(uuid: String, ts: String) = Point(uuid = uuid, latitude = 1.0, longitude = 2.0, timestamp = ts)

    // ---- parseIsoMillis (Important 3, F7 review) ----

    // Every `filterPointsByRange` test below is a RELATIVE comparison
    // between values that all went through `parseIsoMillis`, so a dropped
    // `TimeZone.getTimeZone("UTC")` pin shifts every one of them by the same
    // host-default offset and cancels out of every `>=`/`<=` assertion —
    // none of them would catch that regression. This test instead calls
    // `parseIsoMillis` (widened from `private` to `internal` for exactly
    // this) directly and checks its output against `java.time.Instant`, a
    // parser that is unaffected by the JVM default time zone by
    // construction, so this bites regardless of what cancels elsewhere.
    //
    // Verified by temporarily deleting `parseIsoMillis`'s
    // `timeZone = TimeZone.getTimeZone("UTC")` line: with the JVM default
    // pinned to Asia/Kolkata (+05:30, above), the parsed value comes out
    // exactly 5.5 hours (19_800_000ms) off from the `Instant` reference and
    // `assertEquals` fails. Restored immediately after confirming the
    // failure.
    @Test
    fun `parseIsoMillis parses a Z-suffixed timestamp as true UTC regardless of the JVM default time zone`() {
        val iso = "2026-07-15T12:30:45.123Z"
        assertEquals(Instant.parse(iso).toEpochMilli(), parseIsoMillis(iso))
    }

    @Test
    fun `parseIsoMillis also parses the whole-second pattern as true UTC`() {
        val iso = "2026-07-15T12:30:45Z"
        assertEquals(Instant.parse(iso).toEpochMilli(), parseIsoMillis(iso))
    }

    // ---- filterPointsByRange (pure) ----

    @Test
    fun `filterPointsByRange keeps points within an inclusive from-to range`() {
        val points = listOf(
            point("a", "2026-07-01T00:00:00.000Z"),
            point("b", "2026-07-15T00:00:00.000Z"),
            point("c", "2026-07-30T00:00:00.000Z"),
        )

        val result = History.filterPointsByRange(points, "2026-07-01T00:00:00.000Z", "2026-07-15T00:00:00.000Z")

        assertEquals(listOf("a", "b"), result.map { it.uuid })
    }

    @Test
    fun `filterPointsByRange with only from excludes nothing after it`() {
        val points = listOf(point("a", "2026-07-01T00:00:00.000Z"), point("b", "2026-07-30T00:00:00.000Z"))
        assertEquals(listOf("a", "b"), History.filterPointsByRange(points, "2026-01-01T00:00:00.000Z", null).map { it.uuid })
    }

    @Test
    fun `filterPointsByRange with neither bound returns every point`() {
        val points = listOf(point("a", "2026-07-01T00:00:00.000Z"), point("b", "2026-07-30T00:00:00.000Z"))
        assertEquals(2, History.filterPointsByRange(points, null, null).size)
    }

    @Test
    fun `filterPointsByRange excludes a point before from or after to`() {
        val points = listOf(
            point("early", "2026-06-01T00:00:00.000Z"),
            point("in", "2026-07-15T00:00:00.000Z"),
            point("late", "2026-08-01T00:00:00.000Z"),
        )
        val result = History.filterPointsByRange(points, "2026-07-01T00:00:00.000Z", "2026-07-31T00:00:00.000Z")
        assertEquals(listOf("in"), result.map { it.uuid })
    }

    @Test
    fun `an unparsable point timestamp is dropped, not crashed on`() {
        val points = listOf(point("bad", "not-a-date"), point("good", "2026-07-15T00:00:00.000Z"))
        val result = History.filterPointsByRange(points, null, null)
        assertEquals(listOf("good"), result.map { it.uuid })
    }

    // ---- isoUtc: the Map screen's picked bound -> the range filter ----
    //
    // The string has to be what `parseIsoMillis` reads back, since the range
    // filter compares against the very same string this produces. The default zone is
    // pinned to Asia/Kolkata by `@Before` above, so an un-pinned formatter
    // here would show up as a 5.5-hour skew rather than passing by luck.

    @Test
    fun `isoUtc formats a picked instant as whole-second UTC`() {
        val millis = Instant.parse("2026-07-30T16:42:07Z").toEpochMilli()

        assertEquals("2026-07-30T16:42:07Z", History.isoUtc(millis))
    }

    @Test
    fun `isoUtc round-trips through the parser the range filter uses`() {
        val millis = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli()

        assertEquals(millis, parseIsoMillis(History.isoUtc(millis)))
    }
}
