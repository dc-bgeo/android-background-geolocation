package dev.bgeo.example

import com.bgeo.sdk.Config
import com.bgeo.sdk.ConnectivityChangeEvent
import com.bgeo.sdk.Geofence
import com.bgeo.sdk.GeofenceEvent
import com.bgeo.sdk.GeofencesChangeEvent
import com.bgeo.sdk.HeartbeatEvent
import com.bgeo.sdk.HttpEvent
import com.bgeo.sdk.Location
import com.bgeo.sdk.MotionChangeEvent
import com.bgeo.sdk.ProviderChangeEvent
import com.bgeo.sdk.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `ExampleApp.kt`'s app-level wiring: the bootstrap guard, the
 * `baseConfig` <-> `ConfigSchema` coupling, and the authorization
 * subscription's redact-AND-persist pair.
 *
 * The redaction test deliberately runs through [Bootstrap]'s REAL
 * subscription wiring — [FakeEventSubscriptions] captures the handler
 * `subscribeToEvents` actually registers and fires a real event body at it —
 * rather than calling `LogUploader.logEvent` with a hand-built payload. A
 * test written from the same premise as the code proves nothing (trap 3 in
 * the task brief); this one fails if the subscription is ever rewired to pass
 * the raw event through.
 */
class ExampleAppTest {

    // ---- bootstrap ------------------------------------------------------

    @Test
    fun `bootstrap subscribes once and calls ready once, however many times it runs`() = runTest {
        val events = FakeEventSubscriptions()
        val readyConfigs = mutableListOf<Config>()
        val harness = harness(events = events, onReady = { readyConfigs += it })

        harness.bootstrap.run()
        harness.bootstrap.run()
        harness.bootstrap.run()

        // Subscriptions are never removed, so a second pass would log every
        // event twice and append every point twice.
        assertEquals(1, events.locationHandlers.size)
        assertEquals(1, events.authorizationHandlers.size)
        assertEquals(1, events.httpHandlers.size)
        assertEquals(1, readyConfigs.size)
    }

    @Test
    fun `bootstrap readies with the base config plus persisted overrides`() = runTest {
        val storage = InMemoryStorage()
        storage.putString("bgeo:configOverrides", """{"distanceFilter":42.5}""")
        val readyConfigs = mutableListOf<Config>()
        val harness = harness(storage = storage, onReady = { readyConfigs += it })

        harness.bootstrap.run()

        assertEquals(1, readyConfigs.size)
        // The override wins for its own key; every other baseConfig key
        // survives untouched.
        assertEquals(42.5, readyConfigs[0].distanceFilter!!, 0.0)
        assertEquals(5, readyConfigs[0].stopTimeout)
        assertEquals(true, readyConfigs[0].stopOnTerminate)
        assertEquals(3, readyConfigs[0].logLevel)
        assertEquals(true, harness.store.status.value.ready)
    }

    @Test
    fun `bootstrap restores a persisted device link before ready`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-1","refreshToken":"rt-1"}""",
        )
        val harness = harness(storage = storage)

        harness.bootstrap.run()

        // Without this the Settings screen offers to link an already-linked
        // device and the Map screen's status row reads "not linked".
        assertEquals(true, harness.store.link.value.linked)
        assertEquals("d1", harness.store.link.value.deviceId)
    }

    // ---- trap 5: the authorization event ---------------------------------

    @Test
    fun `the authorization subscription logs no token text into either sink`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-old","refreshToken":"rt-old"}""",
        )
        val events = FakeEventSubscriptions()
        val harness = harness(storage = storage, events = events)
        harness.bootstrap.run()

        events.authorizationHandlers.single().invoke(
            JSONObject(
                """{"success":true,"accessToken":"$ACCESS_TOKEN","refreshToken":"$REFRESH_TOKEN"}""",
            ),
        )

        // Sink 1: the Logs screen's buffer.
        val line = harness.store.logs.value.last { it.event == "onAuthorization" }
        val rendered = "${line.event} ${line.message} ${line.data}"
        assertFalse(rendered.contains(ACCESS_TOKEN))
        assertFalse(rendered.contains(REFRESH_TOKEN))
        // The presence signal survives — a redacted line still has to say
        // whether the refresh produced tokens.
        val data = line.data as JSONObject
        assertTrue(data.getBoolean("success"))
        assertTrue(data.getBoolean("hasAccessToken"))
        assertTrue(data.getBoolean("hasRefreshToken"))

        // Sink 2: the SDK's persisted log queue (`bgeo.db` -> /device/logs).
        val written = harness.written.last()
        assertFalse("${written.second} ${written.third}".contains(ACCESS_TOKEN))
        assertFalse("${written.second} ${written.third}".contains(REFRESH_TOKEN))
    }

    @Test
    fun `the authorization subscription still persists the rotated pair`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-old","refreshToken":"rt-old"}""",
        )
        val events = FakeEventSubscriptions()
        val harness = harness(storage = storage, events = events)
        harness.bootstrap.run()

        events.authorizationHandlers.single().invoke(
            JSONObject("""{"success":true,"accessToken":"$ACCESS_TOKEN","refreshToken":"$REFRESH_TOKEN"}"""),
        )

        // Redacting must not have cost the persistence: both are required.
        // Losing this makes every app-side refresh fail once the engine has
        // rotated the pair natively.
        val stored = JSONObject(storage.getString("bgeo:link")!!)
        assertEquals(ACCESS_TOKEN, stored.getString("accessToken"))
        assertEquals(REFRESH_TOKEN, stored.getString("refreshToken"))
    }

    @Test
    fun `a failed authorization event logs at ERROR with both presence flags false`() = runTest {
        val events = FakeEventSubscriptions()
        val harness = harness(events = events)
        harness.bootstrap.run()

        events.authorizationHandlers.single().invoke(JSONObject("""{"success":false}"""))

        val line = harness.store.logs.value.last { it.event == "onAuthorization" }
        assertEquals(LogLevel.ERROR, line.level)
        assertEquals("failed", line.message)
        val data = line.data as JSONObject
        assertFalse(data.getBoolean("hasAccessToken"))
        assertFalse(data.getBoolean("hasRefreshToken"))
    }

    // ---- the location subscription ---------------------------------------

    @Test
    fun `the location subscription appends the point and reports status`() = runTest {
        val events = FakeEventSubscriptions()
        val harness = harness(events = events)
        harness.bootstrap.run()

        val location = Location.from(
            JSONObject(
                """
                {"uuid":"u-1","timestamp":"2026-07-30T10:00:00.000Z","odometer":12.5,
                 "coords":{"latitude":52.52,"longitude":13.405,"accuracy":9.0,"speed":1.5,"heading":90.0},
                 "activity":{"type":"walking","confidence":90},
                 "battery":{"level":0.42,"is_charging":false},
                 "is_moving":true,"event":"motionchange"}
                """.trimIndent(),
            ),
        )
        assertNotNull(location)
        events.locationHandlers.single().invoke(location!!)

        val point = harness.store.points.value.single()
        assertEquals(52.52, point.latitude, 0.0)
        assertEquals(13.405, point.longitude, 0.0)
        assertEquals("u-1", point.uuid)
        assertEquals("walking", point.activity)
        assertEquals("motionchange", point.event)
        assertEquals(true, point.isMoving)
        assertEquals(true, harness.store.status.value.isMoving)
        assertEquals(0.42, harness.store.status.value.batteryLevel!!, 0.0)
    }

    @Test
    fun `the geofence subscription appends the transition as a point`() = runTest {
        val events = FakeEventSubscriptions()
        val harness = harness(events = events)
        harness.bootstrap.run()

        val event = GeofenceEvent.from(
            JSONObject(
                """
                {"identifier":"home","action":"ENTER",
                 "location":{"uuid":"u-2","timestamp":"2026-07-30T10:01:00.000Z","odometer":13.0,
                   "coords":{"latitude":52.5,"longitude":13.4,"accuracy":8.0},
                   "activity":{"type":"still","confidence":100},
                   "battery":{"level":0.4,"is_charging":false},"is_moving":false}}
                """.trimIndent(),
            ),
        )
        assertNotNull(event)
        events.geofenceHandlers.single().invoke(event!!)

        // Geofence transitions don't ride onLocation — the map and the
        // coordinates table would never show them otherwise.
        val point = harness.store.points.value.single()
        assertEquals("geofence", point.event)
        assertEquals("home", point.geofence?.identifier)
        assertEquals("ENTER", point.geofence?.action)
    }

    // ---- the baseConfig <-> schema coupling ------------------------------

    @Test
    fun `every key baseConfig sets has a schema default that matches it`() {
        // The Settings screen's "default" column and the Reset button both
        // read `ConfigSchema.defaultFor`. If that disagrees with what
        // `ready()` actually boots with, Settings states a value the engine
        // is not running and Reset becomes a behaviour change rather than a
        // revert. Three of these deliberately differ from the engine's own
        // compiled-in fallbacks, so nothing but this test keeps them honest.
        assertEquals(baseConfig.distanceFilter, ConfigSchema.defaultFor("distanceFilter"))
        assertEquals(baseConfig.stopTimeout, ConfigSchema.defaultFor("stopTimeout"))
        assertEquals(baseConfig.stopOnTerminate, ConfigSchema.defaultFor("stopOnTerminate"))
        assertEquals(baseConfig.startOnBoot, ConfigSchema.defaultFor("startOnBoot"))
        assertEquals(baseConfig.debug, ConfigSchema.defaultFor("debug"))
        assertEquals(baseConfig.logLevel, ConfigSchema.defaultFor("logLevel"))
    }

    @Test
    fun `baseConfig sets exactly the six keys the other consoles set`() {
        // A seventh key added here without a matching schema default would
        // reintroduce the same lie the test above exists to prevent, and the
        // assertions there cannot notice a key they don't name.
        val json = baseConfig.toJson()
        assertEquals(
            listOf("debug", "distanceFilter", "logLevel", "startOnBoot", "stopOnTerminate", "stopTimeout"),
            json.keys().asSequence().toList().sorted(),
        )
    }

    // ---- harness ---------------------------------------------------------

    private class Harness(
        val bootstrap: Bootstrap,
        val store: AppStore,
        val written: List<Triple<LogLevel, String, JSONObject?>>,
    )

    private fun CoroutineScope.harness(
        storage: Storage = InMemoryStorage(),
        events: EventSubscriptions = FakeEventSubscriptions(),
        onReady: (Config) -> Unit = {},
    ): Harness {
        val store = AppStore()
        val written = mutableListOf<Triple<LogLevel, String, JSONObject?>>()
        val logUploader = LogUploader(store) { level, message, data -> written += Triple(level, message, data) }
        val deviceLink = DeviceLink(
            http = FakeHttp(),
            storage = storage,
            deviceInfo = DeviceInfo("Pixel 7", "14", "0.1.0"),
            store = store,
            applyConfig = {},
        )
        return Harness(
            bootstrap = Bootstrap(
                store = store,
                configStore = ConfigStore(storage, applyConfig = {}),
                deviceLink = deviceLink,
                geofences = Geofences(
                    store = store,
                    deviceLink = deviceLink,
                    getGeofencesCall = { emptyList<Geofence>() },
                ),
                logUploader = logUploader,
                scope = this,
                events = events,
                readyCall = { config ->
                    onReady(config)
                    State.from(JSONObject("""{"enabled":false,"odometer":0}"""))
                },
            ),
            store = store,
            written = written,
        )
    }

    private companion object {
        // Long enough to clear LogUploader's MIN_SCRUB_LENGTH floor, and
        // shaped like the JWTs the engine really emits.
        const val ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.access-token-payload.signature"
        const val REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiJ9.refresh-token-payload.signature"
    }
}

/** Captures the handlers [Bootstrap.subscribeToEvents] registers, so a test can fire real events at the real wiring. */
private class FakeEventSubscriptions : EventSubscriptions {
    val locationHandlers = mutableListOf<(Location) -> Unit>()
    val motionChangeHandlers = mutableListOf<(MotionChangeEvent) -> Unit>()
    val heartbeatHandlers = mutableListOf<(HeartbeatEvent) -> Unit>()
    val providerChangeHandlers = mutableListOf<(ProviderChangeEvent) -> Unit>()
    val authorizationHandlers = mutableListOf<(JSONObject) -> Unit>()
    val geofenceHandlers = mutableListOf<(GeofenceEvent) -> Unit>()
    val geofencesChangeHandlers = mutableListOf<(GeofencesChangeEvent) -> Unit>()
    val httpHandlers = mutableListOf<(HttpEvent) -> Unit>()
    val connectivityChangeHandlers = mutableListOf<(ConnectivityChangeEvent) -> Unit>()

    override fun onLocation(handler: (Location) -> Unit) { locationHandlers += handler }
    override fun onMotionChange(handler: (MotionChangeEvent) -> Unit) { motionChangeHandlers += handler }
    override fun onHeartbeat(handler: (HeartbeatEvent) -> Unit) { heartbeatHandlers += handler }
    override fun onProviderChange(handler: (ProviderChangeEvent) -> Unit) { providerChangeHandlers += handler }
    override fun onAuthorization(handler: (JSONObject) -> Unit) { authorizationHandlers += handler }
    override fun onGeofence(handler: (GeofenceEvent) -> Unit) { geofenceHandlers += handler }
    override fun onGeofencesChange(handler: (GeofencesChangeEvent) -> Unit) { geofencesChangeHandlers += handler }
    override fun onHttp(handler: (HttpEvent) -> Unit) { httpHandlers += handler }
    override fun onConnectivityChange(handler: (ConnectivityChangeEvent) -> Unit) { connectivityChangeHandlers += handler }
}
