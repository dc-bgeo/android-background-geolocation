package com.bgeo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun `empty Config produces an empty JSONObject`() {
        // setConfig is a PATCH - an untouched Config must change nothing.
        val json = Config().toJson()
        assertEquals(0, json.length())
    }

    @Test
    fun `only set properties appear`() {
        val json = Config(distanceFilter = 30.0, debug = true).toJson()
        assertEquals(2, json.length())
        assertEquals(30.0, json.getDouble("distanceFilter"), 0.0001)
        assertEquals(true, json.getBoolean("debug"))
    }

    @Test
    fun `desiredAccuracy is written as its numeric constant`() {
        val json = Config(desiredAccuracy = DesiredAccuracy.NAVIGATION.value).toJson()
        assertEquals(-2, json.getInt("desiredAccuracy"))
    }

    @Test
    fun `nested notification omits its own nulls`() {
        val json = Config(notification = NotificationConfig(title = "Tracking")).toJson()
        val notification = json.getJSONObject("notification")
        assertEquals("Tracking", notification.getString("title"))
        assertTrue(!notification.has("channelId"))
        assertEquals(1, notification.length())
    }

    @Test
    fun `nested authorization serialises`() {
        val json = Config(
            authorization = AuthorizationConfig(
                strategy = "JWT",
                accessToken = "a",
                refreshToken = "r",
                refreshUrl = "https://example.test/refresh",
            ),
        ).toJson()
        val authorization = json.getJSONObject("authorization")
        assertEquals("a", authorization.getString("accessToken"))
        assertEquals("https://example.test/refresh", authorization.getString("refreshUrl"))
        assertTrue(!authorization.has("refreshPayload"))
    }

    @Test
    fun `clear sentinel emits JSONObject NULL so a key can be unset`() {
        // Flutter cannot express "clear this key" because its Config omits
        // nulls (see flutter/lib/src/config.dart). Kotlin must be able to.
        val json = Config(url = Config.CLEAR_STRING).toJson()
        assertTrue(json.isNull("url"))
    }

    @Test
    fun `clear sentinel works for logUrl headers params extras`() {
        val json = Config(
            logUrl = Config.CLEAR_STRING,
            headers = Config.CLEAR_MAP,
            params = Config.CLEAR_JSON_OBJECT,
            extras = Config.CLEAR_JSON_OBJECT,
        ).toJson()
        assertTrue(json.isNull("logUrl"))
        assertTrue(json.isNull("headers"))
        assertTrue(json.isNull("params"))
        assertTrue(json.isNull("extras"))
    }

    @Test
    fun `clear sentinel works for authorization`() {
        val json = Config(authorization = AuthorizationConfig.CLEAR).toJson()
        assertTrue(json.isNull("authorization"))
    }

    @Test
    fun `non sentinel values are not mistaken for clear`() {
        // A real headers/params/extras payload must serialise normally, not as JSONObject.NULL.
        val json = Config(
            headers = mapOf("Authorization" to "Bearer x"),
            params = JSONObject().put("deviceId", "abc"),
            extras = JSONObject().put("source", "test"),
        ).toJson()
        assertEquals("Bearer x", json.getJSONObject("headers").getString("Authorization"))
        assertEquals("abc", json.getJSONObject("params").getString("deviceId"))
        assertEquals("test", json.getJSONObject("extras").getString("source"))
    }
}
