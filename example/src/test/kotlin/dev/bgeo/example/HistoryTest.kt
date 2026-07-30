package dev.bgeo.example

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

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
}
