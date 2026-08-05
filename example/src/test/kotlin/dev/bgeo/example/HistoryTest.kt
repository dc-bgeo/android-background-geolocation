package dev.bgeo.example

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // this does NOT make `filterPointsByRange`/`load`'s relative
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

    private val linkedStorage = InMemoryStorage().apply {
        putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-1","refreshToken":"rt-1"}""",
        )
    }

    private fun deviceLink(http: FakeHttp, storage: InMemoryStorage = linkedStorage): DeviceLink = DeviceLink(
        http = http,
        storage = storage,
        deviceInfo = DeviceInfo(model = "Pixel 7", osVersion = "14", appVersion = "0.1.0"),
        store = AppStore(),
        applyConfig = {},
    )

    private fun point(uuid: String, ts: String) = Point(uuid = uuid, latitude = 1.0, longitude = 2.0, timestamp = ts)

    // ---- parseIsoMillis (Important 3, F7 review) ----

    // Every `filterPointsByRange`/`load` test below is a RELATIVE comparison
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

    // ---- pointFromServerJson (pure) ----

    @Test
    fun `pointFromServerJson maps every field from the console camelCase shape`() {
        val json = JSONObject()
            .put("uuid", "u1")
            .put("recordedAt", "2026-07-20T10:00:00.000Z")
            .put("lat", 52.52)
            .put("lng", 13.405)
            .put("accuracy", 5.0)
            .put("speed", 2.5)
            .put("heading", 90.0)
            .put("odometer", 1000.0)
            .put("activityType", "walking")
            .put("isMoving", true)
            .put("event", "motionchange")

        val point = History.pointFromServerJson(json)!!

        assertEquals("u1", point.uuid)
        assertEquals("2026-07-20T10:00:00.000Z", point.timestamp)
        assertEquals(52.52, point.latitude, 0.0)
        assertEquals(13.405, point.longitude, 0.0)
        assertEquals(5.0, point.accuracy)
        assertEquals(2.5, point.speed)
        assertEquals(90.0, point.heading)
        assertEquals(1000.0, point.odometer)
        assertEquals("walking", point.activity)
        assertEquals(true, point.isMoving)
        assertEquals("motionchange", point.event)
    }

    @Test
    fun `pointFromServerJson falls back to activity when activityType is absent`() {
        val json = JSONObject().put("recordedAt", "2026-07-20T10:00:00.000Z").put("lat", 1.0).put("lng", 2.0).put("activity", "still")
        assertEquals("still", History.pointFromServerJson(json)!!.activity)
    }

    @Test
    fun `pointFromServerJson returns null when a required field is missing`() {
        val missingLat = JSONObject().put("recordedAt", "2026-07-20T10:00:00.000Z").put("lng", 2.0)
        assertNull(History.pointFromServerJson(missingLat))

        val missingRecordedAt = JSONObject().put("lat", 1.0).put("lng", 2.0)
        assertNull(History.pointFromServerJson(missingRecordedAt))
    }

    @Test
    fun `pointFromServerJson returns null when a required field is the wrong type`() {
        val json = JSONObject().put("recordedAt", "2026-07-20T10:00:00.000Z").put("lat", "not-a-number").put("lng", 2.0)
        assertNull(History.pointFromServerJson(json))
    }

    // ---- load: linked -> server history ----

    @Test
    fun `load fetches server history and reverses newest-first into oldest-first`() = runTest {
        val http = FakeHttp()
        http.enqueue(
            HttpResponse(
                200,
                """{"locations":[
                    {"uuid":"newest","recordedAt":"2026-07-20T10:00:00.000Z","lat":1.0,"lng":2.0},
                    {"uuid":"oldest","recordedAt":"2026-07-20T09:00:00.000Z","lat":1.0,"lng":2.0}
                ]}""",
            ),
        )

        val result = History.load(deviceLink(http), linked = true, localPoints = emptyList())

        assertEquals(listOf("oldest", "newest"), result.map { it.uuid })
    }

    @Test
    fun `load builds the query with limit and encoded from-to`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"locations":[]}"""))

        History.load(deviceLink(http), linked = true, localPoints = emptyList(), from = "2026-07-01T00:00:00.000Z", to = "2026-07-31T00:00:00.000Z")

        val url = http.requests.single().url
        assertTrue(url.startsWith("https://app.bgeo.dev/device/locations?"))
        assertTrue(url.contains("limit=2000"))
        assertTrue(url.contains("from=2026-07-01T00%3A00%3A00.000Z"))
        assertTrue(url.contains("to=2026-07-31T00%3A00%3A00.000Z"))
    }

    @Test
    fun `load omits from-to from the query when neither is given`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"locations":[]}"""))

        History.load(deviceLink(http), linked = true, localPoints = emptyList())

        assertEquals("https://app.bgeo.dev/device/locations?limit=2000", http.requests.single().url)
    }

    // ---- load: not linked, or server failure -> local fallback ----

    @Test
    fun `load never hits the network when not linked`() = runTest {
        val http = FakeHttp()
        val local = listOf(point("a", "2026-07-15T00:00:00.000Z"))

        val result = History.load(deviceLink(http, storage = InMemoryStorage()), linked = false, localPoints = local)

        assertTrue(http.requests.isEmpty())
        assertEquals(listOf("a"), result.map { it.uuid })
    }

    @Test
    fun `load falls back to the local buffer when the server request fails`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(500, """{"detail":"server exploded"}"""))
        val local = listOf(point("a", "2026-07-15T00:00:00.000Z"))

        val result = History.load(deviceLink(http), linked = true, localPoints = local)

        assertEquals(listOf("a"), result.map { it.uuid })
    }

    @Test
    fun `load falls back to the local buffer when the response body is malformed`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, "not json"))
        val local = listOf(point("a", "2026-07-15T00:00:00.000Z"))

        val result = History.load(deviceLink(http), linked = true, localPoints = local)

        assertEquals(listOf("a"), result.map { it.uuid })
    }

    @Test
    fun `load falls back to the local buffer when a 401 refresh also fails, swallowing the DeviceLinkError`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{}"""))
        http.enqueue(HttpResponse(400, """{"detail":"refresh token revoked"}"""))
        val local = listOf(point("a", "2026-07-15T00:00:00.000Z"))

        val result = History.load(deviceLink(http), linked = true, localPoints = local) // must not throw

        assertEquals(listOf("a"), result.map { it.uuid })
    }

    @Test
    fun `load applies the from-to range filter to the local fallback too`() = runTest {
        val http = FakeHttp()
        val local = listOf(point("early", "2026-06-01T00:00:00.000Z"), point("in", "2026-07-15T00:00:00.000Z"))

        val result = History.load(
            deviceLink(http, storage = InMemoryStorage()),
            linked = false,
            localPoints = local,
            from = "2026-07-01T00:00:00.000Z",
        )

        assertEquals(listOf("in"), result.map { it.uuid })
    }

    // ---- isoUtc: the Map screen's picked bound -> the wire ----
    //
    // Both directions matter and neither is implied by the other: the string
    // has to be what the server's `from`/`to` accept AND what
    // `parseIsoMillis` reads back, since the local-buffer fallback path
    // filters with the very same string this produces. The default zone is
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
