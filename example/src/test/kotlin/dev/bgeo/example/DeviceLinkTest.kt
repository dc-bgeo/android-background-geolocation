package dev.bgeo.example

import com.bgeo.sdk.AuthorizationConfig
import com.bgeo.sdk.Config
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        http: Http,
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
        val applied = mutableListOf<Config>()
        val link = deviceLink(http, storage = storage, applyConfig = applied)

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

        // The SDK config applied on link() — this is what points the native
        // uploader at the right server with the right bearer token and
        // refresh wiring. Wrong here means uploads 401 forever once the app
        // is killed and refresh never fires.
        assertEquals(1, applied.size)
        val config = applied[0]
        assertEquals("https://app.bgeo.dev/device/locations", config.url)
        assertEquals("https://app.bgeo.dev/device/logs", config.logUrl)
        assertEquals(true, config.autoSync)
        assertEquals(true, config.batchSync)
        assertEquals(50, config.maxBatchSize)
        val authorization = config.authorization!!
        assertEquals("JWT", authorization.strategy)
        assertEquals("at-1", authorization.accessToken)
        assertEquals("rt-1", authorization.refreshToken)
        assertEquals("https://app.bgeo.dev/device/auth/refresh", authorization.refreshUrl)
        assertEquals("{refreshToken}", authorization.refreshPayload?.getString("refresh_token"))
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
        // The refresh request must carry the refresh token, not the access
        // token — this is the credential that survives the access token
        // expiring, and it's what the server's /device/auth/refresh expects.
        val refreshBody = JSONObject(http.requests[1].body!!)
        assertEquals("r-old", refreshBody.getString("refresh_token"))
        assertEquals(1, refreshBody.length())
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
    fun `a non-JSON 2xx register response throws DeviceLinkError, not JSONException`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, "<html>captive portal</html>"))
        val link = deviceLink(http)

        try {
            link.link("https://app.bgeo.dev", "code")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("register response malformed", e.message)
        }
    }

    @Test
    fun `a 2xx register response missing access_token throws DeviceLinkError`() = runTest {
        val http = FakeHttp()
        http.enqueue(HttpResponse(200, """{"device_id":"dev-1"}"""))
        val link = deviceLink(http)

        try {
            link.link("https://app.bgeo.dev", "code")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("register response malformed", e.message)
        }
    }

    @Test
    fun `a non-JSON 2xx refresh response throws DeviceLinkError, not JSONException`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"stale","refreshToken":"r-old"}""",
        )
        val http = FakeHttp()
        http.enqueue(HttpResponse(401, """{}"""))
        http.enqueue(HttpResponse(200, "not json"))
        val link = deviceLink(http, storage = storage)

        try {
            link.authorizedFetch("/device/geofences")
            fail("expected DeviceLinkError")
        } catch (e: DeviceLinkError) {
            assertEquals("refresh response malformed", e.message)
        }
    }

    @Test
    fun `StoredLink toString redacts both tokens`() {
        val link = StoredLink(
            serverUrl = "https://app.bgeo.dev",
            deviceId = "dev-1",
            accessToken = "secret-access-token",
            refreshToken = "secret-refresh-token",
        )

        val text = link.toString()

        assertTrue(!text.contains("secret-access-token"))
        assertTrue(!text.contains("secret-refresh-token"))
    }

    @Test
    fun `HttpRequest toString redacts the bearer token and the body`() {
        val request = HttpRequest(
            method = "POST",
            url = "https://app.bgeo.dev/device/auth/refresh",
            headers = mapOf("Authorization" to "Bearer secret-access-token"),
            body = """{"refresh_token":"secret-refresh-token"}""",
        )

        val text = request.toString()

        assertTrue(!text.contains("secret-access-token"))
        assertTrue(!text.contains("secret-refresh-token"))
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
        // logUrl must be cleared the same way as url — otherwise the engine
        // keeps POSTing this device's logs to the server it just unlinked
        // from, now with the auth block stripped.
        assertEquals(Config.CLEAR_STRING, applied[0].logUrl)
        assertEquals(AuthorizationConfig.CLEAR, applied[0].authorization)
        // Not the Flutter-style empty-string workaround.
        assertTrue(applied[0].url != "")
        assertTrue(applied[0].logUrl != "")
        assertNull(storage.getString("bgeo:link"))
        assertEquals(false, store.link.value.linked)
        assertNull(store.link.value.deviceId)
    }

    @Test
    fun `two concurrent 401s produce exactly one refresh request`() = runTest {
        val storage = InMemoryStorage()
        storage.putString(
            "bgeo:link",
            """{"serverUrl":"https://app.bgeo.dev","deviceId":"d1","accessToken":"stale","refreshToken":"r-old"}""",
        )
        val http = ConcurrentRefreshFakeHttp()
        val link = deviceLink(http, storage = storage)

        // Simulates the geofence sync and history load firing together on
        // screen entry: both hit the stale access token's 401 before either
        // has a chance to refresh.
        val first = async { link.authorizedFetch("/device/geofences") }
        val second = async { link.authorizedFetch("/device/history") }
        val (firstResponse, secondResponse) = listOf(first, second).awaitAll()

        assertEquals(1, http.refreshCallCount)
        assertEquals(200, firstResponse.status)
        assertEquals(200, secondResponse.status)

        val stored = JSONObject(storage.getString("bgeo:link")!!)
        assertEquals("fresh", stored.getString("accessToken"))
    }
}

/**
 * [Http] fake for the concurrent-401 test: the first two calls to any
 * non-refresh URL return 401 (one per racing caller), every call after that
 * succeeds. A `delay` on both branches forces the two [DeviceLinkTest]
 * coroutines to actually interleave under `runTest`'s virtual scheduler —
 * without it, one call would run to completion before the other starts, and
 * the race this test exists to catch would never occur.
 */
private class ConcurrentRefreshFakeHttp : Http {
    var refreshCallCount = 0
        private set
    private var nonRefreshCallCount = 0

    override suspend fun send(request: HttpRequest): HttpResponse {
        if (request.url.endsWith("/device/auth/refresh")) {
            refreshCallCount++
            kotlinx.coroutines.delay(20)
            return HttpResponse(200, """{"access_token":"fresh","refresh_token":"r-new"}""")
        }
        nonRefreshCallCount++
        val callIndex = nonRefreshCallCount
        kotlinx.coroutines.delay(10)
        return if (callIndex <= 2) HttpResponse(401, "{}") else HttpResponse(200, """{"ok":true}""")
    }
}
