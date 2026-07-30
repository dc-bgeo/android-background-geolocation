package dev.bgeo.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
