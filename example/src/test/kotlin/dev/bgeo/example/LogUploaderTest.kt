package dev.bgeo.example

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [LogUploader.logEvent] must (1) actually carry its `data` payload through
 * to both sinks — the largest single parity gap iOS's review found, where
 * `data` was accepted but silently dropped — and (2) never let a credential
 * string reach either sink. Every "redacts" test below asserts on the
 * ABSENCE of the raw token text, not merely the presence of an unrelated
 * field like `success`, per the task brief: a naive passthrough
 * implementation would still have a `success` key and would still fail
 * these.
 */
class LogUploaderTest {

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    // Fix round 1 (F7 review): same convention as
    // `CoordinatesSheetLogicTest` — pin the JVM default Locale/TimeZone away
    // from US/UTC so a regression that drops `isoTimestamp()`'s own explicit
    // `Locale.US`/UTC pin (falling back to whatever `Locale`/`TimeZone`
    // default the JVM happens to have) fails a test instead of silently
    // passing because the dev/CI machine's default already happens to be
    // UTC. "Asia/Kolkata" (UTC+05:30, no DST) is used instead of a
    // DST-observing zone so the offset is a fixed, unambiguous constant.
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

    private class RecordedWrite(val level: LogLevel, val message: String, val data: JSONObject?)

    private fun uploader(store: AppStore, writes: MutableList<RecordedWrite>): LogUploader =
        LogUploader(store = store) { level, message, data -> writes += RecordedWrite(level, message, data) }

    // ---- data payload actually carried through (the iOS parity gap) ----

    @Test
    fun `logEvent appends a log line carrying the given level, message and data to the store`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val data = JSONObject().put("count", 3)

        uploader.logEvent("onLocation", LogLevel.INFO, message = "fix received", data = data)

        val line = store.logs.value.single()
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("onLocation", line.event)
        assertEquals("fix received", line.message)
        assertEquals(3, (line.data as JSONObject).getInt("count"))
    }

    @Test
    fun `logEvent forwards the same data to the SDK logger wrapped with the event name`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val data = JSONObject().put("count", 3)

        uploader.logEvent("onLocation", LogLevel.INFO, data = data)

        val write = writes.single()
        assertEquals(LogLevel.INFO, write.level)
        assertEquals("onLocation", write.message) // message defaults to the event name
        assertEquals("onLocation", write.data?.getString("event"))
        assertEquals(3, write.data?.getJSONObject("data")?.getInt("count"))
    }

    @Test
    fun `a message overrides the event name in the SDK-facing write, but the payload still carries the event`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)

        uploader.logEvent("stop", LogLevel.ERROR, message = "stop failed: timeout")

        val write = writes.single()
        assertEquals("stop failed: timeout", write.message)
        assertEquals("stop", write.data?.getString("event"))
        assertFalse(write.data!!.has("data")) // no data passed -> no "data" key at all
    }

    @Test
    fun `every level routes to its own write call`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)

        LogLevel.entries.forEach { level -> uploader.logEvent("e", level) }

        assertEquals(LogLevel.entries, writes.map { it.level })
    }

    // Fix round 1 (F7 review, Important 3): `isoTimestamp()` pins
    // `Locale.US` + UTC internally regardless of the JVM default (pinned to
    // `pl-PL`/`Asia/Kolkata` above). This asserts on `ts`'s actual value —
    // parsed back with an independent UTC formatter and checked against a
    // tight real-clock window — which no prior test in this file did.
    // Verified by temporarily deleting `isoTimestamp()`'s
    // `format.timeZone = TimeZone.getTimeZone("UTC")` line: `ts` then
    // renders Kolkata (+05:30) wall-clock time with a literal `Z` suffix,
    // landing ~5.5h outside the window below, and this test fails. Restored
    // immediately after confirming the failure.
    @Test
    fun `logEvent stamps ts as true UTC regardless of the JVM default time zone`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val before = System.currentTimeMillis()

        uploader.logEvent("start", LogLevel.INFO)

        val after = System.currentTimeMillis()
        val ts = store.logs.value.single().ts
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val parsedMillis = parser.parse(ts)!!.time
        assertTrue(parsedMillis in (before - 2_000)..(after + 2_000))
    }

    // ---- redaction: the two things that matter most ----

    @Test
    fun `an onAuthorization-shaped body never lets either token string reach the store`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val accessToken = "eyJhbGciOiJIUzI1NiJ9.access.secret"
        val refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh.secret"
        val body = JSONObject().put("success", true).put("accessToken", accessToken).put("refreshToken", refreshToken)

        uploader.logEvent("onAuthorization", LogLevel.INFO, data = body)

        val stored = store.logs.value.single().data as JSONObject
        val storedText = stored.toString()
        assertFalse(storedText.contains(accessToken))
        assertFalse(storedText.contains(refreshToken))
        // The `success` field is not what proves redaction — the token
        // absence above is — but it should still survive untouched.
        assertTrue(stored.getBoolean("success"))
    }

    @Test
    fun `an onAuthorization-shaped body never lets either token string reach the SDK-facing uploader payload`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val accessToken = "eyJhbGciOiJIUzI1NiJ9.access.secret"
        val refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh.secret"
        val body = JSONObject().put("success", true).put("accessToken", accessToken).put("refreshToken", refreshToken)

        uploader.logEvent("onAuthorization", LogLevel.INFO, data = body)

        val payloadText = writes.single().data!!.toString()
        assertFalse(payloadText.contains(accessToken))
        assertFalse(payloadText.contains(refreshToken))
    }

    @Test
    fun `redaction preserves the keys as a redacted marker, not a dropped field`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val body = JSONObject().put("accessToken", "secret-at").put("refreshToken", "secret-rt")

        uploader.logEvent("onAuthorization", LogLevel.INFO, data = body)

        val stored = store.logs.value.single().data as JSONObject
        assertEquals("<redacted>", stored.getString("accessToken"))
        assertEquals("<redacted>", stored.getString("refreshToken"))
    }

    @Test
    fun `a snake_case token key nested one level down is also redacted`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val refreshToken = "secret-refresh-token-value"
        val body = JSONObject().put("tokens", JSONObject().put("refresh_token", refreshToken))

        uploader.logEvent("someEvent", LogLevel.INFO, data = body)

        val stored = store.logs.value.single().data as JSONObject
        val storedText = stored.toString()
        assertFalse(storedText.contains(refreshToken))
        assertEquals("<redacted>", stored.getJSONObject("tokens").getString("refresh_token"))
    }

    // Minor 4 (F7 review): the only prior nested-token test nested inside a
    // `JSONObject`, never a `JSONArray` element, so `redactArray`
    // (`LogUploader.kt`) was correct on inspection but unproven by a test.
    @Test
    fun `a token nested inside a JSONArray element is also redacted`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val accessToken = "secret-in-an-array-element"
        val body = JSONObject().put("sessions", JSONArray().put(JSONObject().put("accessToken", accessToken)))

        uploader.logEvent("someEvent", LogLevel.INFO, data = body)

        val stored = store.logs.value.single().data as JSONObject
        val storedText = stored.toString()
        assertFalse(storedText.contains(accessToken))
        assertEquals("<redacted>", stored.getJSONArray("sessions").getJSONObject(0).getString("accessToken"))
    }

    // ---- Important 2 (F7 review): widened key matching, both directions ----

    @Test
    fun `a bare 'token' key, 'Authorization' header, 'bearer', 'jwt' and 'id_token'-'idToken' are all redacted`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val body = JSONObject()
            .put("token", "secret-token")
            .put("headers", JSONObject().put("Authorization", "Bearer secret-bearer-value"))
            .put("bearer", "secret-bearer-2")
            .put("jwt", "secret-jwt")
            .put("id_token", "secret-id-token-1")
            .put("idToken", "secret-id-token-2")

        uploader.logEvent("onHttp", LogLevel.DEBUG, data = body)

        val stored = store.logs.value.single().data as JSONObject
        assertEquals("<redacted>", stored.getString("token"))
        assertEquals("<redacted>", stored.getJSONObject("headers").getString("Authorization"))
        assertEquals("<redacted>", stored.getString("bearer"))
        assertEquals("<redacted>", stored.getString("jwt"))
        assertEquals("<redacted>", stored.getString("id_token"))
        assertEquals("<redacted>", stored.getString("idToken"))
    }

    // The false-positive direction matters as much as the true-positive one
    // — these are real/plausible diagnostic field names (`accuracyAuthorization`
    // is `onProviderChange`'s actual field) that merely CONTAIN "token" or
    // "authorization" as part of a longer name. A substring/contains match
    // would redact them into uselessness; the Logs tab exists to show
    // exactly this kind of diagnostic.
    @Test
    fun `keys that merely contain 'token' or 'authorization' as part of a longer name are left untouched`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val body = JSONObject()
            .put("tokenCount", 3)
            .put("authorizationStatus", "granted")
            .put("accuracyAuthorization", "full")

        uploader.logEvent("onProviderChange", LogLevel.WARN, data = body)

        val stored = store.logs.value.single().data as JSONObject
        assertEquals(3, stored.getInt("tokenCount"))
        assertEquals("granted", stored.getString("authorizationStatus"))
        assertEquals("full", stored.getString("accuracyAuthorization"))
    }

    // Important 1 (F7 review): a call site that echoes the same token into a
    // human-readable `message` (instead of iOS's literal "refreshed") must
    // not leak it there just because `data` was cleaned.
    @Test
    fun `a token echoed into message is scrubbed there too, once it's known sensitive from data`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val accessToken = "leaked-if-message-not-scrubbed"
        val body = JSONObject().put("accessToken", accessToken)

        uploader.logEvent("onAuthorization", LogLevel.INFO, message = "refreshed: $accessToken", data = body)

        val stored = store.logs.value.single()
        assertFalse(stored.message!!.contains(accessToken))
        assertEquals("refreshed: <redacted>", stored.message)
        val write = writes.single()
        assertFalse(write.message.contains(accessToken))
    }

    @Test
    fun `a non-sensitive field with token-like text in its value is left untouched`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)
        val body = JSONObject().put("message", "contains the word accessToken but is not one")

        uploader.logEvent("someEvent", LogLevel.INFO, data = body)

        val stored = store.logs.value.single().data as JSONObject
        assertEquals("contains the word accessToken but is not one", stored.getString("message"))
    }

    @Test
    fun `no data payload is handled without throwing and produces no data key`() {
        val store = AppStore()
        val writes = mutableListOf<RecordedWrite>()
        val uploader = uploader(store, writes)

        uploader.logEvent("start", LogLevel.INFO, message = "tracking started")

        assertNull(store.logs.value.single().data)
        assertFalse(writes.single().data!!.has("data"))
    }
}
