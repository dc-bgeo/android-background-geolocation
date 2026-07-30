package dev.bgeo.example

import com.bgeo.sdk.Geofence
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Add / edit (an upsert through [Geofences.add], same as RN/iOS) / delete
 * each must hit the engine FIRST and only push the full snapshot to
 * `PUT {base}/device/geofences` — and only update [AppStore] — after that
 * engine call succeeds. Every "failed engine call" test below would fail
 * against a naive implementation that pushes/updates unconditionally: a
 * push-before-engine-call (or a swallowed engine exception) still leaves the
 * fake HTTP client holding a request / the store holding the new list, which
 * every assertion here checks is exactly zero/unchanged.
 */
class GeofencesTest {

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

    private fun fence(id: String = "home", radius: Double = 200.0) =
        Geofence(
            identifier = id, radius = radius, latitude = 52.52, longitude = 13.405,
            notifyOnEntry = true, notifyOnExit = true, notifyOnDwell = false, loiteringDelay = null, extras = null,
        )

    // ---- add ----

    @Test
    fun `add calls the engine first, then pushes the full snapshot and updates the store`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"ok":true}"""))
        val store = AppStore()
        val engineCalls = mutableListOf<Geofence>()
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            addGeofenceCall = { g -> engineCalls += g },
            getGeofencesCall = { listOf(fence()) },
        )

        geofences.add(fence())

        assertEquals(1, engineCalls.size)
        assertEquals(listOf(fence()), store.geofences.value)
        assertEquals(1, http.requests.size)
        assertEquals("PUT", http.requests[0].method)
        assertEquals("https://app.bgeo.dev/device/geofences", http.requests[0].url)
        val body = JSONObject(http.requests[0].body!!)
        assertEquals(1, body.getJSONArray("geofences").length())
        assertEquals("home", body.getJSONArray("geofences").getJSONObject(0).getString("identifier"))
    }

    @Test
    fun `a failing engine add pushes nothing and leaves the store untouched`() = runTest {
        val http = FakeHttp()
        val store = AppStore()
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            addGeofenceCall = { throw RuntimeException("engine rejected geofence") },
            getGeofencesCall = { listOf(fence()) },
        )

        try {
            geofences.add(fence())
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("engine rejected geofence", e.message)
        }

        assertTrue(store.geofences.value.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    // ---- edit (an upsert through add, per Task 5's geometry-keyed change detection) ----

    @Test
    fun `editing an existing geofence (same identifier, new radius) pushes the updated snapshot`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"ok":true}"""))
        val store = AppStore()
        val edited = fence(radius = 350.0)
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            addGeofenceCall = {},
            getGeofencesCall = { listOf(edited) },
        )

        geofences.add(edited)

        assertEquals(listOf(edited), store.geofences.value)
        val body = JSONObject(http.requests[0].body!!)
        assertEquals(350.0, body.getJSONArray("geofences").getJSONObject(0).getDouble("radius"), 0.0)
    }

    @Test
    fun `a failing engine edit pushes nothing and leaves the store untouched`() = runTest {
        val http = FakeHttp()
        val store = AppStore()
        store.setGeofences(listOf(fence(radius = 200.0)))
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            addGeofenceCall = { throw RuntimeException("engine rejected edit") },
            getGeofencesCall = { listOf(fence(radius = 999.0)) },
        )

        try {
            geofences.add(fence(radius = 999.0))
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            // expected
        }

        // Store keeps the OLD snapshot (radius 200), never the attempted edit.
        assertEquals(200.0, store.geofences.value.single().radius, 0.0)
        assertTrue(http.requests.isEmpty())
    }

    // ---- remove ----

    @Test
    fun `remove calls the engine first, then pushes the full snapshot and updates the store`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"ok":true}"""))
        val store = AppStore()
        store.setGeofences(listOf(fence()))
        val engineCalls = mutableListOf<String>()
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            removeGeofenceCall = { id -> engineCalls += id },
            getGeofencesCall = { emptyList() },
        )

        geofences.remove("home")

        assertEquals(listOf("home"), engineCalls)
        assertTrue(store.geofences.value.isEmpty())
        assertEquals(1, http.requests.size)
        assertEquals("PUT", http.requests[0].method)
        assertEquals(0, JSONObject(http.requests[0].body!!).getJSONArray("geofences").length())
    }

    @Test
    fun `a failing engine remove pushes nothing and leaves the store untouched`() = runTest {
        val http = FakeHttp()
        val store = AppStore()
        store.setGeofences(listOf(fence()))
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http),
            removeGeofenceCall = { throw RuntimeException("engine rejected removal") },
            getGeofencesCall = { emptyList() },
        )

        try {
            geofences.remove("home")
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            // expected
        }

        // Store still lists the fence — the device never actually dropped it.
        assertEquals(listOf(fence()), store.geofences.value)
        assertTrue(http.requests.isEmpty())
    }

    // ---- refresh / not-linked no-op ----

    @Test
    fun `refresh pushes nothing but still updates the store when not linked`() = runTest {
        val http = FakeHttp()
        val store = AppStore()
        val geofences = Geofences(
            store = store,
            deviceLink = deviceLink(http, storage = InMemoryStorage()), // no stored link
            getGeofencesCall = { listOf(fence()) },
        )

        geofences.refresh()

        assertEquals(listOf(fence()), store.geofences.value)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `refresh swallows a DeviceLinkError from the push (expired refresh token) without throwing`() = runTest {
        // authorizedFetch's 401 -> refresh -> refresh itself fails -> throws
        // DeviceLinkError. Geofences.refresh() must swallow this, not
        // propagate it as a save failure to the caller.
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{}"""))
        http.enqueue(HttpResponse(400, """{"detail":"refresh token revoked"}"""))
        val store = AppStore()
        val geofences = Geofences(store = store, deviceLink = deviceLink(http), getGeofencesCall = { listOf(fence()) })

        geofences.refresh() // must not throw

        assertEquals(listOf(fence()), store.geofences.value)
        assertEquals(2, http.requests.size)
    }
}
