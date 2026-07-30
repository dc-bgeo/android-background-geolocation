package dev.bgeo.example.components

import dev.bgeo.example.Point
import dev.bgeo.example.PointGeofence
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * [PointFormat] is the pure formatting logic pulled out of the
 * `CoordinatesSheet` composable so it's testable without a Compose
 * instrumentation harness (this module has none) — same reasoning as
 * `SettingsScreenLogicTest`. [PointFormat.roundedOrDash] is also this task's
 * concrete "guard every Double->Int conversion" site: accuracy, odometer and
 * heading all round through it.
 */
class CoordinatesSheetLogicTest {

    private lateinit var previousDefault: TimeZone
    private lateinit var previousLocale: Locale

    @Before
    fun fixTimeZone() {
        previousDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousDefault)
    }

    // Fix round 1 (F7): pinned to a comma-decimal locale so a regression that
    // drops `Locale.US` from `PointFormat.coordinate`/`nonNegative` (e.g.
    // reverting to plain `.format(...)`) fails these tests instead of
    // silently passing under a US-default CI/dev machine.
    @Before
    fun fixLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `time formats a fractional-second ISO timestamp as local HH-mm-ss`() {
        assertEquals("14:05:09", PointFormat.time("2026-07-29T14:05:09.123Z"))
    }

    @Test
    fun `time formats a whole-second ISO timestamp`() {
        assertEquals("14:05:09", PointFormat.time("2026-07-29T14:05:09Z"))
    }

    @Test
    fun `time falls back to placeholder for a malformed timestamp`() {
        assertEquals("--:--:--", PointFormat.time("not-a-date"))
    }

    @Test
    fun `coordinate formats to five decimal places by default`() {
        assertEquals("52.52000", PointFormat.coordinate(52.52))
    }

    @Test
    fun `nonNegative dashes a null value`() {
        assertEquals("–", PointFormat.nonNegative(null, digits = 1))
    }

    @Test
    fun `nonNegative dashes a negative value`() {
        assertEquals("–", PointFormat.nonNegative(-1.0, digits = 1))
    }

    @Test
    fun `nonNegative formats a valid value`() {
        assertEquals("3.4", PointFormat.nonNegative(3.44, digits = 1))
    }

    // ---- roundedOrDash: the guarded Double->Int display path (trap 7) ----

    @Test
    fun `roundedOrDash dashes null`() {
        assertEquals("–", PointFormat.roundedOrDash(null))
    }

    @Test
    fun `roundedOrDash rounds an ordinary value`() {
        assertEquals("42", PointFormat.roundedOrDash(41.6))
    }

    @Test
    fun `roundedOrDash dashes NaN instead of coercing it to zero`() {
        assertEquals("–", PointFormat.roundedOrDash(Double.NaN))
    }

    @Test
    fun `roundedOrDash dashes infinity instead of saturating to Int MAX_VALUE`() {
        assertEquals("–", PointFormat.roundedOrDash(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `heading dashes a negative value`() {
        assertEquals("–", PointFormat.heading(-1.0))
    }

    @Test
    fun `heading rounds and appends a degree sign`() {
        assertEquals("270°", PointFormat.heading(270.4))
    }

    @Test
    fun `isMoving renders the tri-state`() {
        assertEquals("–", PointFormat.isMoving(null))
        assertEquals("yes", PointFormat.isMoving(true))
        assertEquals("no", PointFormat.isMoving(false))
    }

    @Test
    fun `eventLabel renders identifier and action for a geofence point`() {
        val point = Point(
            latitude = 1.0, longitude = 2.0, timestamp = "2026-07-29T00:00:00Z",
            event = "geofence", geofence = PointGeofence(identifier = "home", action = "enter"),
        )
        assertEquals("home · ENTER", PointFormat.eventLabel(point))
    }

    @Test
    fun `eventLabel falls back to the raw event name for a non-geofence point`() {
        val point = Point(latitude = 1.0, longitude = 2.0, timestamp = "2026-07-29T00:00:00Z", event = "motionchange")
        assertEquals("motionchange", PointFormat.eventLabel(point))
    }

    @Test
    fun `eventLabel dashes a point with no event`() {
        val point = Point(latitude = 1.0, longitude = 2.0, timestamp = "2026-07-29T00:00:00Z")
        assertEquals("-", PointFormat.eventLabel(point))
    }
}
