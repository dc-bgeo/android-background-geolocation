package com.bgeo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct tests of the `JSONObject.*OrNull` helpers in `JsonDecoding.kt`.
 *
 * The three helpers must behave consistently: present-and-correct-type ->
 * value; absent, JSON `null`, or a value of the wrong type -> `null`. `org.
 * json`'s raw `optInt`/`optBoolean` instead *coerce* an unparseable value
 * down to `0`/`false` - a required field arriving as garbage would then
 * decode successfully with a fabricated value rather than failing the
 * record, which is worse than a dropped record. These tests exercise the
 * exact mistype scenario (a `String` where a `Number`/`Boolean` is expected)
 * against all three helpers to prove they agree.
 */
class JsonDecodingTest {

    @Test
    fun `intOrNull returns the value when present and correctly typed`() {
        assertEquals(88, JSONObject().put("confidence", 88).intOrNull("confidence"))
    }

    @Test
    fun `intOrNull returns null when the key is absent`() {
        assertNull(JSONObject().intOrNull("confidence"))
    }

    @Test
    fun `intOrNull returns null for a JSON null rather than the org json default`() {
        assertNull(JSONObject().put("confidence", JSONObject.NULL).intOrNull("confidence"))
    }

    @Test
    fun `intOrNull returns null rather than coercing a mistyped string to zero`() {
        // org.json's own optInt("confidence") would silently return 0 here -
        // a fabricated value for a field that was never actually an Int.
        val json = JSONObject().put("confidence", "oops")
        assertNull(json.intOrNull("confidence"))
    }

    @Test
    fun `boolOrNull returns the value when present and correctly typed`() {
        assertEquals(true, JSONObject().put("is_charging", true).boolOrNull("is_charging"))
    }

    @Test
    fun `boolOrNull returns null when the key is absent`() {
        assertNull(JSONObject().boolOrNull("is_charging"))
    }

    @Test
    fun `boolOrNull returns null for a JSON null rather than the org json default`() {
        assertNull(JSONObject().put("is_charging", JSONObject.NULL).boolOrNull("is_charging"))
    }

    @Test
    fun `boolOrNull returns null rather than coercing a mistyped string to false`() {
        // org.json's own optBoolean("is_charging") would silently return
        // false here - a fabricated value for a field that was never
        // actually a Boolean.
        val json = JSONObject().put("is_charging", "oops")
        assertNull(json.boolOrNull("is_charging"))
    }

    @Test
    fun `doubleOrNull already returns null rather than coercing a mistyped string`() {
        // Unlike optInt/optBoolean, org.json's optDouble fails a non-numeric
        // String to NaN, which the isNaN() check already turns into null -
        // this is the behaviour intOrNull/boolOrNull were fixed to match.
        val json = JSONObject().put("level", "oops")
        assertNull(json.doubleOrNull("level"))
    }

    @Test
    fun `doubleOrNull survives a whole number stored as Int`() {
        // org.json stores whole numbers as Int; doubleOrNull must still read them.
        assertEquals(1234.0, JSONObject().put("odometer", 1234).doubleOrNull("odometer")!!, 0.0001)
    }
}
