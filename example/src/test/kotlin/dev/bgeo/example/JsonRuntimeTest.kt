package dev.bgeo.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the coupled pair of settings in build.gradle.kts:
 * `unitTests.isReturnDefaultValues = true` (needed because the engine touches
 * Looper in a class initializer) stubs org.json too, and the stub silently
 * no-ops. The real `org.json:json` testImplementation shadows it.
 *
 * If this test fails, every JSON test in this module has become vacuous —
 * fix the build file, do not delete this test.
 */
class JsonRuntimeTest {
    @Test
    fun `org_json is the real implementation, not the android_jar stub`() {
        val json = JSONObject()
        json.put("n", 42)
        json.put("s", "hello")
        assertEquals(42, json.getInt("n"))
        assertEquals("hello", json.getString("s"))
        assertEquals(2, json.length())
        assertEquals(42, JSONObject(json.toString()).getInt("n"))
    }
}
