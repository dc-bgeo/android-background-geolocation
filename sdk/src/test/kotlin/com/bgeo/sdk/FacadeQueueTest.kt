package com.bgeo.sdk

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FacadeQueueTest {

    private lateinit var engine: FakeEngine

    @Before
    fun setUp() {
        engine = FakeEngine()
        BackgroundGeolocation.engine = engine
        BackgroundGeolocation.hub = EventHub()
    }

    @After
    fun tearDown() {
        BackgroundGeolocation.engine = LiveEngine
        BackgroundGeolocation.hub = EventHub()
    }

    private fun sampleLocationJson(uuid: String = "sample-uuid"): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("timestamp", "2026-07-29T12:00:00.000Z")
        put("odometer", 12.3)
        put("coords", JSONObject().apply {
            put("latitude", 1.0)
            put("longitude", 2.0)
            put("accuracy", 5.0)
        })
        put("activity", JSONObject().apply {
            put("type", "still")
            put("confidence", 100)
        })
        put("battery", JSONObject().apply {
            put("level", 0.5)
            put("is_charging", false)
        })
    }

    private fun locationsEnvelope(vararg jsons: JSONObject): JSONObject =
        JSONObject().put("locations", JSONArray().apply { jsons.forEach { put(it) } })

    // ---- sync -----------------------------------------------------------

    @Test
    fun `sync returns the pre-sync snapshot and calls sync once`() = runTest {
        engine.stubbedGetLocations = FakeEngine.Outcome.success(locationsEnvelope(sampleLocationJson()))
        // Simulate the queue draining the instant engine.sync() is called --
        // if the facade read the snapshot AFTER this, it would see the
        // now-empty queue instead of the pre-sync records.
        engine.onSync = { engine.stubbedGetLocations = FakeEngine.Outcome.success(locationsEnvelope()) }

        val result = BackgroundGeolocation.sync()

        assertEquals(1, result.size)
        assertEquals("sample-uuid", result.first().uuid)
        assertEquals(1, engine.syncCallCount)
    }

    // ---- getLocations -----------------------------------------------------

    @Test
    fun `getLocations unwraps and decodes`() = runTest {
        engine.stubbedGetLocations = FakeEngine.Outcome.success(locationsEnvelope(sampleLocationJson()))
        val locations = BackgroundGeolocation.getLocations()
        assertEquals(1, locations.size)
        assertEquals("sample-uuid", locations.first().uuid)
    }

    @Test
    fun `getLocations skips a malformed record rather than failing the call`() = runTest {
        val malformed = JSONObject().put("uuid", "broken") // missing required fields
        engine.stubbedGetLocations = FakeEngine.Outcome.success(locationsEnvelope(sampleLocationJson(), malformed))
        val locations = BackgroundGeolocation.getLocations()
        assertEquals(1, locations.size)
        assertEquals("sample-uuid", locations.first().uuid)
    }

    // ---- destroyLocations ---------------------------------------------------

    @Test
    fun `destroyLocations unwraps the count`() = runTest {
        engine.stubbedDestroyLocations = FakeEngine.Outcome.success(JSONObject().put("count", 3))
        val count = BackgroundGeolocation.destroyLocations()
        assertEquals(3, count)
    }

    // ---- getCount -----------------------------------------------------------

    @Test
    fun `getCount passes through`() = runTest {
        engine.stubbedPendingCount = 7
        assertEquals(7, BackgroundGeolocation.getCount())
    }

    // ---- destroyLocation ------------------------------------------------------

    @Test
    fun `destroyLocation throws NotFound when the engine returns false`() = runTest {
        engine.stubbedDestroyLocation = false
        try {
            BackgroundGeolocation.destroyLocation("missing-uuid")
            fail("expected NOT_FOUND")
        } catch (e: BGeoException.NotFound) {
            assertEquals("NOT_FOUND", e.code)
        }
        assertEquals(listOf("missing-uuid"), engine.destroyLocationUuids)
    }

    @Test
    fun `destroyLocation succeeds silently when the engine returns true`() = runTest {
        engine.stubbedDestroyLocation = true
        BackgroundGeolocation.destroyLocation("present-uuid")
        assertEquals(listOf("present-uuid"), engine.destroyLocationUuids)
    }

    // ---- insertLocation -------------------------------------------------------

    @Test
    fun `insertLocation forwards the location to the engine`() = runTest {
        val location = JSONObject().put("uuid", "inserted")
        BackgroundGeolocation.insertLocation(location)
        assertEquals(listOf(location), engine.insertedLocations)
    }

    // ---- getAuthState -----------------------------------------------------

    @Test
    fun `getAuthState maps both tokens`() = runTest {
        engine.stubbedAuthStateMap = JSONObject().put("accessToken", "at-1").put("refreshToken", "rt-1")
        val state = BackgroundGeolocation.getAuthState()
        assertEquals("at-1", state.accessToken)
        assertEquals("rt-1", state.refreshToken)
    }

    @Test
    fun `getAuthState maps nulls when the engine reports no tokens`() = runTest {
        engine.stubbedAuthStateMap = JSONObject().put("accessToken", JSONObject.NULL).put("refreshToken", JSONObject.NULL)
        val state = BackgroundGeolocation.getAuthState()
        assertNull(state.accessToken)
        assertNull(state.refreshToken)
    }

    // ---- getLog -------------------------------------------------------------

    @Test
    fun `getLog caps the limit at 5000`() = runTest {
        BackgroundGeolocation.getLog(limit = 999_999)
        assertEquals(listOf(5000), engine.newestLogsLimits)
    }

    @Test
    fun `getLog floors the limit at 1`() = runTest {
        BackgroundGeolocation.getLog(limit = 0)
        assertEquals(listOf(1), engine.newestLogsLimits)
    }

    @Test
    fun `getLog passes through an in-range limit`() = runTest {
        BackgroundGeolocation.getLog(limit = 250)
        assertEquals(listOf(250), engine.newestLogsLimits)
    }

    @Test
    fun `getLog decodes entries`() = runTest {
        engine.stubbedNewestLogs = listOf(
            JSONObject().put("ts", "2026-01-01T00:00:00.000Z").put("level", 3).put("src", "native").put("event", "app"),
        )
        val entries = BackgroundGeolocation.getLog()
        assertEquals(1, entries.size)
        assertEquals("native", entries.first().src)
    }

    @Test
    fun `getLog skips a malformed row rather than failing the call`() = runTest {
        val malformed = JSONObject().put("ts", "2026-01-01T00:00:00.000Z") // missing level/src/event
        engine.stubbedNewestLogs = listOf(
            JSONObject().put("ts", "2026-01-01T00:00:00.000Z").put("level", 3).put("src", "native").put("event", "app"),
            malformed,
        )
        val entries = BackgroundGeolocation.getLog()
        assertEquals(1, entries.size)
        assertEquals("native", entries.first().src)
    }

    // ---- destroyLog -----------------------------------------------------------

    @Test
    fun `destroyLog returns the engine's count`() = runTest {
        engine.stubbedDeleteAllLogs = 42
        assertEquals(42, BackgroundGeolocation.destroyLog())
    }

    // ---- uploadLog --------------------------------------------------------

    @Test
    fun `uploadLog returns the pre-flush pending count and calls flushLogs once`() = runTest {
        engine.stubbedPendingLogCount = 5
        // Simulate the log store draining the instant flushLogs() is called --
        // if the facade read the pending count AFTER this, it would report
        // zero instead of the pre-flush count.
        engine.onFlushLogs = { engine.stubbedPendingLogCount = 0 }

        val count = BackgroundGeolocation.uploadLog()

        assertEquals(5, count)
        assertEquals(1, engine.flushLogsCallCount)
    }

    // ---- logger -------------------------------------------------------------

    @Test
    fun `each logger level writes the right numeric level with native src and the default tag`() {
        val logger = BackgroundGeolocation.logger
        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")
        logger.verbose("v")

        assertEquals(listOf(1, 2, 3, 4, 5), engine.logCalls.map { it.level })
        assertEquals(List(5) { "native" }, engine.logCalls.map { it.src })
        assertEquals(List(5) { "BGGeo" }, engine.logCalls.map { it.tag })
        assertEquals(List(5) { "app" }, engine.logCalls.map { it.event })
        assertEquals(listOf("e", "w", "i", "d", "v"), engine.logCalls.map { it.message })
    }

    @Test
    fun `logger accepts a custom tag`() {
        BackgroundGeolocation.logger.info("hi", tag = "MyTag")
        assertEquals("MyTag", engine.logCalls.last().tag)
    }

    @Test
    fun `logger omits data when not provided`() {
        BackgroundGeolocation.logger.info("no data")
        assertNull(engine.logCalls.last().data)
    }

    @Test
    fun `logger encodes data as a JSON string`() {
        BackgroundGeolocation.logger.info("with data", data = JSONObject().put("a", 1))
        assertEquals("{\"a\":1}", engine.logCalls.last().data)
    }
}
