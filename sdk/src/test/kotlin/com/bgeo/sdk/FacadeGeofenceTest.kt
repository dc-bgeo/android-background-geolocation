package com.bgeo.sdk

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FacadeGeofenceTest {

    private lateinit var engine: FakeEngine

    private val home = Geofence(
        identifier = "home", radius = 200.0, latitude = 1.0, longitude = 2.0,
        notifyOnEntry = null, notifyOnExit = null, notifyOnDwell = null, loiteringDelay = null, extras = null,
    )
    private val office = Geofence(
        identifier = "office", radius = 100.0, latitude = 3.0, longitude = 4.0,
        notifyOnEntry = null, notifyOnExit = null, notifyOnDwell = null, loiteringDelay = null, extras = null,
    )

    @Before
    fun setUp() {
        engine = FakeEngine()
        BackgroundGeolocation.engine = engine
        BackgroundGeolocation.hub = EventHub()
        BackgroundGeolocation.hub.attach(engine)
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

    // ---- addGeofence / addGeofences ----------------------------------------

    @Test
    fun `addGeofence forwards a one-element array`() = runTest {
        BackgroundGeolocation.addGeofence(home)
        assertEquals(1, engine.addGeofencesCalls.size)
        val forwarded = engine.addGeofencesCalls.first()!!
        assertEquals(1, forwarded.length())
        assertEquals("home", forwarded.getJSONObject(0).getString("identifier"))
    }

    @Test
    fun `addGeofences forwards all elements`() = runTest {
        BackgroundGeolocation.addGeofences(listOf(home, office))
        assertEquals(1, engine.addGeofencesCalls.size)
        val forwarded = engine.addGeofencesCalls.first()!!
        assertEquals(2, forwarded.length())
        assertEquals("home", forwarded.getJSONObject(0).getString("identifier"))
        assertEquals(200.0, forwarded.getJSONObject(0).getDouble("radius"), 0.0)
        assertEquals("office", forwarded.getJSONObject(1).getString("identifier"))
    }

    @Test
    fun `addGeofences throws the engine's INVALID_GEOFENCE code verbatim`() = runTest {
        engine.stubbedAddGeofences = FakeEngine.Outcome.failure("INVALID_GEOFENCE", "geofence validation failed")
        try {
            BackgroundGeolocation.addGeofences(listOf(home))
            fail("expected a rejection")
        } catch (e: BGeoException) {
            assertEquals("INVALID_GEOFENCE", e.code)
        }
    }

    @Test
    fun `addGeofences throws the engine's LICENSE_EXPIRED code verbatim`() = runTest {
        engine.stubbedAddGeofences = FakeEngine.Outcome.failure("LICENSE_EXPIRED", "BGeo license check failed (LICENSE_EXPIRED)")
        try {
            BackgroundGeolocation.addGeofences(listOf(home))
            fail("expected a rejection")
        } catch (e: BGeoException) {
            assertEquals("LICENSE_EXPIRED", e.code)
        }
    }

    // ---- removeGeofence / removeGeofences ----------------------------------

    @Test
    fun `removeGeofence delegates to the engine`() = runTest {
        BackgroundGeolocation.removeGeofence("home")
        assertEquals(listOf("home"), engine.removeGeofenceIdentifiers)
    }

    @Test
    fun `removeGeofences delegates to the engine`() = runTest {
        BackgroundGeolocation.removeGeofences()
        assertEquals(1, engine.removeGeofencesCallCount)
    }

    // ---- getGeofences -----------------------------------------------------

    @Test
    fun `getGeofences unwraps and decodes`() = runTest {
        engine.stubbedGetGeofences = FakeEngine.Outcome.success(
            JSONObject().put("geofences", JSONArray().put(home.toJson()).put(office.toJson())),
        )
        val geofences = BackgroundGeolocation.getGeofences()
        assertEquals(listOf("home", "office"), geofences.map { it.identifier })
    }

    @Test
    fun `getGeofences skips a malformed record rather than failing the call`() = runTest {
        val malformed = JSONObject().put("identifier", "broken") // missing radius/lat/lng
        engine.stubbedGetGeofences = FakeEngine.Outcome.success(
            JSONObject().put("geofences", JSONArray().put(home.toJson()).put(malformed)),
        )
        val geofences = BackgroundGeolocation.getGeofences()
        assertEquals(listOf("home"), geofences.map { it.identifier })
    }

    // ---- geofenceExists -----------------------------------------------------

    @Test
    fun `geofenceExists passes through`() = runTest {
        engine.stubbedGeofenceExists = FakeEngine.Outcome.success(JSONObject().put("exists", true))
        val exists = BackgroundGeolocation.geofenceExists("home")
        assertTrue(exists)
        assertEquals(listOf("home"), engine.geofenceExistsIdentifiers)
    }

    @Test
    fun `geofenceExists returns false when the engine says false`() = runTest {
        engine.stubbedGeofenceExists = FakeEngine.Outcome.success(JSONObject().put("exists", false))
        assertFalse(BackgroundGeolocation.geofenceExists("home"))
    }

    @Test
    fun `geofenceExists returns false when the exists key is absent`() = runTest {
        engine.stubbedGeofenceExists = FakeEngine.Outcome.success(JSONObject())
        assertFalse(BackgroundGeolocation.geofenceExists("home"))
    }

    @Test
    fun `geofenceExists returns false when the exists key is mistyped`() = runTest {
        engine.stubbedGeofenceExists = FakeEngine.Outcome.success(JSONObject().put("exists", "yes"))
        assertFalse(BackgroundGeolocation.geofenceExists("home"))
    }

    // ---- onGeofence ---------------------------------------------------------

    @Test
    fun `onGeofence decodes a geofence event with its action`() {
        var received: GeofenceEvent? = null
        BackgroundGeolocation.onGeofence { received = it }
        engine.emit(
            "geofence",
            JSONObject()
                .put("identifier", "home")
                .put("action", "ENTER")
                .put("location", sampleLocationJson()),
        )
        assertEquals("home", received?.identifier)
        assertEquals(GeofenceAction.ENTER, received?.action)
        assertEquals("sample-uuid", received?.location?.uuid)
    }

    // ---- onGeofencesChange ----------------------------------------------------

    @Test
    fun `onGeofencesChange decodes both on and off arrays`() {
        var received: GeofencesChangeEvent? = null
        BackgroundGeolocation.onGeofencesChange { received = it }
        engine.emit(
            "geofenceschange",
            JSONObject()
                .put("on", JSONArray().put(home.toJson()))
                .put("off", JSONArray().put(office.toJson())),
        )
        assertEquals(listOf("home"), received?.on?.map { it.identifier })
        assertEquals(listOf("office"), received?.off?.map { it.identifier })
    }

    // ---- I3: every suspend member here hops off the calling thread --------
    //
    // GeofenceStore persists via direct SQLite (BGGeoDb.kt's `geofences`
    // table), the same main-safety concern as Queue.kt/Logger.kt.

    private fun assertHoppedOffCallingThread(method: String, callingThread: Thread) {
        val recorded = engine.callThreads[method]
        assertNotNull("expected FakeEngine.$method to have been called", recorded)
        assertNotEquals(
            "expected a Dispatchers.IO hop off ${callingThread.name} for $method, but it ran on the same thread",
            callingThread,
            recorded,
        )
    }

    @Test
    fun `addGeofences resolves off the calling thread`() = runTest {
        val callingThread = Thread.currentThread()
        BackgroundGeolocation.addGeofences(listOf(home))
        assertHoppedOffCallingThread("addGeofences", callingThread)
    }

    @Test
    fun `removeGeofence resolves off the calling thread`() = runTest {
        val callingThread = Thread.currentThread()
        BackgroundGeolocation.removeGeofence("home")
        assertHoppedOffCallingThread("removeGeofence", callingThread)
    }

    @Test
    fun `removeGeofences resolves off the calling thread`() = runTest {
        val callingThread = Thread.currentThread()
        BackgroundGeolocation.removeGeofences()
        assertHoppedOffCallingThread("removeGeofences", callingThread)
    }

    @Test
    fun `getGeofences resolves off the calling thread`() = runTest {
        val callingThread = Thread.currentThread()
        engine.stubbedGetGeofences = FakeEngine.Outcome.success(JSONObject().put("geofences", JSONArray()))
        BackgroundGeolocation.getGeofences()
        assertHoppedOffCallingThread("getGeofences", callingThread)
    }

    @Test
    fun `geofenceExists resolves off the calling thread`() = runTest {
        val callingThread = Thread.currentThread()
        engine.stubbedGeofenceExists = FakeEngine.Outcome.success(JSONObject().put("exists", true))
        BackgroundGeolocation.geofenceExists("home")
        assertHoppedOffCallingThread("geofenceExists", callingThread)
    }
}
