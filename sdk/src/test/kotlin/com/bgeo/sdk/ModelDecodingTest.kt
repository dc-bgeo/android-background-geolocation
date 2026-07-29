package com.bgeo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDecodingTest {

    /** A full Location payload in exactly the shape the engine emits. */
    private fun sampleLocationJson(): JSONObject = JSONObject().apply {
        put("uuid", "abc-123")
        put("timestamp", "2026-07-29T10:00:00.000Z")
        put("odometer", 1234.5)
        put("is_moving", true)
        put(
            "coords",
            JSONObject().apply {
                put("latitude", 52.2297)
                put("longitude", 21.0122)
                put("accuracy", 12.0)
                put("altitude", 110.0)
                put("altitude_accuracy", 3.0)
                put("speed", 4.2)
                put("speed_accuracy", 0.5)
                put("heading", 91.0)
                put("heading_accuracy", 2.0)
                put("ellipsoidal_altitude", 140.0)
            },
        )
        put("activity", JSONObject().put("type", "in_vehicle").put("confidence", 88))
        put("battery", JSONObject().put("level", 0.62).put("is_charging", false))
        put("extras", JSONObject().put("watch", true))
    }

    @Test
    fun `location decodes every field including snake case wire keys`() {
        val location = Location.from(sampleLocationJson())
        assertNotNull(location)
        assertEquals("abc-123", location!!.uuid)
        assertEquals("2026-07-29T10:00:00.000Z", location.timestamp)
        assertEquals(1234.5, location.odometer, 0.0001)
        assertEquals(true, location.isMoving)
        assertEquals(52.2297, location.coords.latitude, 0.0001)
        assertEquals(21.0122, location.coords.longitude, 0.0001)
        assertEquals(12.0, location.coords.accuracy, 0.0001)
        assertEquals(110.0, location.coords.altitude!!, 0.0001)
        assertEquals(3.0, location.coords.altitudeAccuracy!!, 0.0001)
        assertEquals(4.2, location.coords.speed!!, 0.0001)
        assertEquals(0.5, location.coords.speedAccuracy!!, 0.0001)
        assertEquals(91.0, location.coords.heading!!, 0.0001)
        assertEquals(2.0, location.coords.headingAccuracy!!, 0.0001)
        assertEquals(140.0, location.coords.ellipsoidalAltitude!!, 0.0001)
        assertEquals(ActivityType.IN_VEHICLE, location.activity.type)
        assertEquals(88, location.activity.confidence)
        assertEquals(0.62, location.battery.level, 0.0001)
        assertEquals(false, location.battery.isCharging)
        assertEquals(true, location.extras?.getBoolean("watch"))
    }

    @Test
    fun `location decodes with only required fields`() {
        val json = sampleLocationJson()
        json.put("coords", JSONObject().put("latitude", 1.0).put("longitude", 2.0).put("accuracy", 3.0))
        json.remove("extras")
        val location = Location.from(json)
        assertNotNull(location)
        assertNull(location!!.coords.altitude)
        assertNull(location.extras)
    }

    @Test
    fun `location decoding fails when a required field is missing`() {
        val json = sampleLocationJson()
        json.remove("uuid")
        assertNull(Location.from(json))
    }

    @Test
    fun `location decodes with JSONObject NULL is_moving as false rather than failing`() {
        // The engine sends JSONObject.NULL, not false, while a cold-started
        // session's first fixes are still in the "unconfirmed MOVING" probing
        // window (BGGeoEngine.kt:109-115) - up to stopTimeout (default 5 min)
        // after start(). is_moving must coerce to false, not be required, or
        // every location in that window fails to decode.
        val json = sampleLocationJson()
        json.put("is_moving", JSONObject.NULL)
        val location = Location.from(json)
        assertNotNull("a JSONObject.NULL is_moving must not fail the whole decode", location)
        assertEquals(false, location!!.isMoving)
    }

    @Test
    fun `numeric fields survive being encoded as ints`() {
        // org.json stores whole numbers as Int; optDouble must still read them.
        val json = sampleLocationJson().put("odometer", 1234)
        assertEquals(1234.0, Location.from(json)!!.odometer, 0.0001)
    }

    @Test
    fun `motion change event accepts absent location`() {
        // Android omits `location` on the first motionchange of a session.
        val event = MotionChangeEvent.from(JSONObject().put("isMoving", true))
        assertNotNull(event)
        assertEquals(true, event!!.isMoving)
        assertNull(event.location)
    }

    @Test
    fun `motion change event accepts JSONObject NULL location`() {
        val json = JSONObject().put("isMoving", false).put("location", JSONObject.NULL)
        val event = MotionChangeEvent.from(json)
        assertNotNull(event)
        assertEquals(false, event!!.isMoving)
        assertNull(event.location)
    }

    @Test
    fun `unknown activity type falls back to UNKNOWN rather than failing`() {
        val activity = MotionActivity.from(JSONObject().put("type", "teleporting").put("confidence", 10))
        assertNotNull(activity)
        assertEquals(ActivityType.UNKNOWN, activity!!.type)
    }

    @Test
    fun `state exposes typed enabled and keeps unknown diagnostic keys`() {
        val json = JSONObject().apply {
            put("enabled", true)
            put("odometer", 42.0)
            put("someFutureDiagnosticKey", 7)
        }
        val state = State.from(json)
        assertNotNull(state)
        assertEquals(true, state!!.enabled)
        assertEquals(42.0, state["odometer"])
        assertEquals(7, state["someFutureDiagnosticKey"])
    }

    @Test
    fun `state defaults enabled to false when the key is absent rather than failing`() {
        // Non-failable in practice: the engine always resolves stateMap(),
        // never rejects, so decoding must not fail even on a payload missing keys.
        val state = State.from(JSONObject())
        assertNotNull(state)
        assertEquals(false, state!!.enabled)
        assertNull(state["enabled"])
    }

    @Test
    fun `geofence round-trips through toJson`() {
        val geofence = Geofence(
            identifier = "home",
            radius = 150.0,
            latitude = 52.0,
            longitude = 21.0,
            notifyOnEntry = true,
            notifyOnExit = true,
            notifyOnDwell = false,
            loiteringDelay = 30_000.0,
            extras = JSONObject().put("kind", "home"),
        )
        val decoded = Geofence.from(geofence.toJson())
        assertNotNull(decoded)
        assertEquals("home", decoded!!.identifier)
        assertEquals(150.0, decoded.radius, 0.0001)
        assertEquals(false, decoded.notifyOnDwell)
        assertEquals(30_000.0, decoded.loiteringDelay!!, 0.0001)
        assertEquals("home", decoded.extras?.getString("kind"))
    }

    @Test
    fun `toJson omits null optionals`() {
        val geofence = Geofence(
            identifier = "x",
            radius = 100.0,
            latitude = 0.0,
            longitude = 0.0,
            notifyOnEntry = null,
            notifyOnExit = null,
            notifyOnDwell = null,
            loiteringDelay = null,
            extras = null,
        )
        val json = geofence.toJson()
        assertTrue(!json.has("notifyOnEntry"))
        assertTrue(!json.has("loiteringDelay"))
        assertTrue(!json.has("extras"))
    }

    @Test
    fun `geofence event decodes its action`() {
        val json = JSONObject()
            .put("identifier", "home")
            .put("action", "DWELL")
            .put("location", sampleLocationJson())
        val event = GeofenceEvent.from(json)
        assertNotNull(event)
        assertEquals(GeofenceAction.DWELL, event!!.action)
        assertEquals("home", event.identifier)
    }

    @Test
    fun `provider state decodes typed enums`() {
        val json = JSONObject().apply {
            put("status", 3)
            put("enabled", true)
            put("gps", true)
            put("network", false)
            put("accuracyAuthorization", 1)
        }
        val providerState = ProviderState.from(json)
        assertNotNull(providerState)
        assertEquals(AuthorizationStatus.ALWAYS, providerState!!.status)
        assertEquals(AccuracyAuthorization.REDUCED, providerState.accuracyAuthorization)
        assertEquals(false, providerState.network)
    }

    @Test
    fun `provider state falls back rather than failing on an empty json object`() {
        val providerState = ProviderState.from(JSONObject())
        assertNotNull(providerState)
        assertEquals(AuthorizationStatus.NOT_DETERMINED, providerState!!.status)
        assertEquals(false, providerState.enabled)
        assertEquals(false, providerState.gps)
        assertEquals(false, providerState.network)
        assertNull(providerState.accuracyAuthorization)
    }

    @Test
    fun `geofences change event decodes on and off lists`() {
        val home = Geofence(
            identifier = "home", radius = 100.0, latitude = 1.0, longitude = 2.0,
            notifyOnEntry = null, notifyOnExit = null, notifyOnDwell = null,
            loiteringDelay = null, extras = null,
        )
        val work = home.copy(identifier = "work")
        val json = JSONObject()
            .put("on", org.json.JSONArray().put(home.toJson()))
            .put("off", org.json.JSONArray().put(work.toJson()))
        val event = GeofencesChangeEvent.from(json)
        assertNotNull(event)
        assertEquals(1, event!!.on.size)
        assertEquals("home", event.on[0].identifier)
        assertEquals(1, event.off.size)
        assertEquals("work", event.off[0].identifier)
    }

    @Test
    fun `http event decodes`() {
        val json = JSONObject().put("success", false).put("status", 0).put("responseText", "offline")
        val event = HttpEvent.from(json)
        assertNotNull(event)
        assertEquals(false, event!!.success)
        assertEquals(0, event.status)
        assertEquals("offline", event.responseText)
    }

    @Test
    fun `connectivity change event decodes`() {
        val event = ConnectivityChangeEvent.from(JSONObject().put("connected", true))
        assertNotNull(event)
        assertEquals(true, event!!.connected)
    }

    @Test
    fun `heartbeat event wraps the raw json`() {
        val json = JSONObject().put("odometer", 5.0)
        val event = HeartbeatEvent.from(json)
        assertEquals(5.0, event.raw.getDouble("odometer"), 0.0001)
    }
}
