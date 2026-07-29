package com.bgeo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against a silent regression in the unit-test classpath: AGP's
 * `isReturnDefaultValues` (needed so BGGeoEngine's class initializer can run
 * on a plain JVM — see SmokeTest) applies to ALL of android.jar, including
 * org.json. Without a real `org.json:json` testImplementation shadowing that
 * stub, JSONObject silently no-ops instead of doing real work, and every
 * later task's JSON-backed tests (typed models, Config, event hub) would
 * either NPE or pass vacuously. If this test starts failing, the stub has
 * won back the classpath.
 */
class JsonRuntimeTest {
    @Test
    fun `JSONObject genuinely round-trips values instead of no-op stubbing`() {
        val o = JSONObject().put("a", 1).put("b", "x")

        assertEquals(1, o.getInt("a"))
        assertEquals("x", o.getString("b"))
        assertTrue(o.has("a"))
        assertFalse(o.isNull("a"))
        assertTrue(o.isNull("missing"))
    }
}
