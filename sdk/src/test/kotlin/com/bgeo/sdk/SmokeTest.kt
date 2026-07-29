package com.bgeo.sdk

import org.junit.Assert.assertNotNull
import org.junit.Test

class SmokeTest {
    @Test
    fun `engine dependency resolves and its public surface is visible`() {
        // Referencing the engine object proves the AAR resolved from libs/ and
        // that its public API survived R8 (consumer-rules keeps com.bgeo.*).
        assertNotNull(com.bgeo.BGGeoEngine)
    }
}
