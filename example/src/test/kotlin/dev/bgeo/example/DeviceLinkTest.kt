package dev.bgeo.example

import com.bgeo.sdk.AuthorizationConfig
import com.bgeo.sdk.Config
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceLinkTest {

    private val deviceInfo = DeviceInfo(model = "Pixel 7", osVersion = "14", appVersion = "0.1.0")

    private fun deviceLink(
        http: FakeHttp,
        storage: Storage = InMemoryStorage(),
        store: AppStore = AppStore(),
        applyConfig: MutableList<Config> = mutableListOf(),
    ): DeviceLink = DeviceLink(
        http = http,
        storage = storage,
        deviceInfo = deviceInfo,
        store = store,
        applyConfig = { config -> applyConfig.add(config) },
    )

    @Test
    fun `link posts the exact register body and stores the returned tokens`() = runTest {
        val http = FakeHttp()
        http.enqueue(
            HttpResponse(
                200,
                """{"device_id":"dev-1","access_token":"at-1","refresh_token":"rt-1"}""",
            ),
        )
        val storage = InMemoryStorage()
        val link = deviceLink(http, storage = storage)

        val result = link.link(serverUrl = "https://app.bgeo.dev", code = "  ABC123  ")

        assertEquals(1, http.requests.size)
        val request = http.requests[0]
        assertEquals("POST", request.method)
        assertEquals("https://app.bgeo.dev/device/register", request.url)

        val body = JSONObject(request.body!!)
        assertEquals("ABC123", body.getString("code"))
        val device = body.getJSONObject("device")
        assertTrue(device.getString("uuid").isNotEmpty())
        assertEquals("Pixel 7", device.getString("model"))
        assertEquals("android", device.getString("platform"))
        assertEquals("14", device.getString("osVersion"))
        assertEquals("0.1.0", device.getString("appVersion"))
        assertEquals("BGeoExample (android)", device.getString("name"))

        assertEquals("dev-1", result.deviceId)
        assertEquals("at-1", result.accessToken)
        assertEquals("rt-1", result.refreshToken)

        // Stored for later authorizedFetch/refresh calls.
        val stored = JSONObject(storage.getString("bgeo:link")!!)
        assertEquals("at-1", stored.getString("accessToken"))
        assertEquals("rt-1", stored.getString("refreshToken"))
        assertEquals("dev-1", stored.getString("deviceId"))
    }

    @Test
    fun `link reuses a persisted install uuid across calls`() = runTest {
        val storage = InMemoryStorage()
        val http1 = FakeHttp().apply {
            enqueue(HttpResponse(200, """{"device_id":"d1","access_token":"a1","refresh_token":"r1"}"""))
        }
        deviceLink(http1, storage = storage).link("https://app.bgeo.dev", "code1")
        val firstUuid = JSONObject(http1.requests[0].body!!).getJSONObject("device").getString("uuid")

        val http2 = FakeHttp().apply {
            enqueue(HttpResponse(200, """{"device_id":"d2","access_token":"a2","refresh_token":"r2"}"""))
        }
        deviceLink(http2, storage = storage).link("https://app.bgeo.dev", "code2")
        val secondUuid = JSONObject(http2.requests[0].body!!).getJSONObject("device").getString("uuid")

        assertEquals(firstUuid, secondUuid)
    }

    @Test
    fun `non-2xx register response surfaces detail as the error message`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(400, """{"detail":"invalid code"}"""))
        val link = deviceLink(http)

        try {
            link.link("https://app.bgeo.dev", "bad-code")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("invalid code", e.message)
        }
    }

    @Test
    fun `non-2xx register response falls back to error when detail is absent`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(500, """{"error":"server exploded"}"""))
        val link = deviceLink(http)

        try {
            link.link("https://app.bgeo.dev", "code")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("server exploded", e.message)
        }
    }

    @Test
    fun `authorizedFetch attaches the bearer token`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-1","refreshToken":"rt-1"}""",
        )
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"ok":true}"""))
        val link = deviceLink(http, storage = storage)

        val response = link.authorizedFetch("/device/geofences")

        assertEquals(200, response.status)
        assertEquals(1, http.requests.size)
        assertEquals("Bearer at-1", http.requests[0].headers["Authorization"])
        assertEquals("https://app.bgeo.dev/device/geofences", http.requests[0].url)
    }

    @Test
    fun `a 401 refreshes once and retries once, returning the retry result`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"stale","refreshToken":"r-old"}""",
        )
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{"detail":"expired"}"""))
        http.enqueue(HttpResponse(200, """{"access_token":"fresh","refresh_token":"r-new"}"""))
        http.enqueue(HttpResponse(200, """{"geofences":[]}"""))
        val link = deviceLink(http, storage = storage)

        val response = link.authorizedFetch("/device/geofences")

        assertEquals(3, http.requests.size)
        assertEquals("https://app.bgeo.dev/device/auth/refresh", http.requests[1].url)
        assertEquals("Bearer fresh", http.requests[2].headers["Authorization"])
        assertEquals(200, response.status)
        assertEquals("""{"geofences":[]}""", response.body)

        val stored = JSONObject(storage.getString("bgeo:link")!!)
        assertEquals("fresh", stored.getString("accessToken"))
        assertEquals("r-new", stored.getString("refreshToken"))
    }

    @Test
    fun `a second 401 after refresh does not loop`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"stale","refreshToken":"r-old"}""",
        )
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{}"""))
        http.enqueue(HttpResponse(200, """{"access_token":"fresh","refresh_token":"r-new"}"""))
        http.enqueue(HttpResponse(401, """{}"""))
        val link = deviceLink(http, storage = storage)

        val response = link.authorizedFetch("/device/geofences")

        // Exactly 3 requests total: original + refresh + one retry. A fourth
        // request here would mean the retry looped instead of giving up.
        assertEquals(3, http.requests.size)
        assertEquals(401, response.status)
    }

    @Test
    fun `a failing refresh surfaces its own error, not the original 401`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"stale","refreshToken":"r-old"}""",
        )
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{}"""))
        http.enqueue(HttpResponse(400, """{"detail":"refresh token revoked"}"""))
        val link = deviceLink(http, storage = storage)

        try {
            link.authorizedFetch("/device/geofences")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("refresh token revoked", e.message)
        }

        // No retry attempted once the refresh itself failed.
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `unlink clears the config via the CLEAR sentinel, not empty strings`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"at-1","refreshToken":"rt-1"}""",
        )
        val applied = mutableListOf<Config>()
        val store = AppStore()
        val link = deviceLink(FakeHttp(), storage = storage, store = store, applyConfig = applied)

        link.unlink()

        assertEquals(1, applied.size)
        assertEquals(Config.CLEAR_STRING, applied[0].url)
        assertEquals(AuthorizationConfig.CLEAR, applied[0].authorization)
        // Not the Flutter-style empty-string workaround.
        assertTrue(applied[0].url != "")
        assertNull(storage.getString("bgeo:link"))
        assertEquals(false, store.link.value.linked)
        assertNull(store.link.value.deviceId)
    }
}
