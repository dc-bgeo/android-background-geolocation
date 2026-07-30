package dev.bgeo.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigSchemaTest {

    // ---- pinned engine defaults (Task 4 brief: "for every key ... especially
    // these four" — grepped directly against core/android/engine, not copied
    // from another console). Regressing any of these means the schema has
    // drifted from the engine again. ----

    @Test
    fun `stationaryRadius default is 200 (BGGeoEngine kt 636)`() {
        assertEquals(200.0, ConfigSchema.defaultFor("stationaryRadius"))
    }

    @Test
    fun `minimumActivityRecognitionConfidence default is 75, Android's own value, not iOS's 50 (BGGeoEngine kt 870)`() {
        assertEquals(75, ConfigSchema.defaultFor("minimumActivityRecognitionConfidence"))
    }

    @Test
    fun `httpTimeoutMs default is 30000 (BGGeoHttpStore kt 199)`() {
        assertEquals(30000, ConfigSchema.defaultFor("httpTimeoutMs"))
    }

    @Test
    fun `maxBatchSize default is -1, unbatched (BGGeoHttpStore kt 60,198)`() {
        assertEquals(-1, ConfigSchema.defaultFor("maxBatchSize"))
    }

    @Test
    fun `defaultFor returns null for a key not in any section`() {
        assertNull(ConfigSchema.defaultFor("notAKey"))
    }

    @Test
    fun `fields is the flattened union of every section's fields`() {
        val expected = ConfigSchema.sections.sumOf { it.fields.size }
        assertEquals(expected, ConfigSchema.fields.size)
    }

    @Test
    fun `every field key is unique`() {
        val keys = ConfigSchema.fields.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `keysWithPrefix returns every notification field`() {
        val keys = ConfigSchema.keysWithPrefix("notification.")
        assertEquals(
            setOf(
                "notification.title", "notification.text", "notification.channelId",
                "notification.channelName", "notification.smallIcon", "notification.color",
                "notification.priority",
            ),
            keys.toSet(),
        )
    }

    // ---- ConfigCoerce.numberFromText: trap 7 (String.toDouble()/Double.toInt()
    // silently produce Infinity/NaN/saturated-Int instead of throwing) ----

    @Test
    fun `numberFromText parses a valid int`() {
        assertEquals(42, ConfigCoerce.numberFromText("42", isIntKind = true))
    }

    @Test
    fun `numberFromText parses a valid double`() {
        assertEquals(3.14, ConfigCoerce.numberFromText("3.14", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects an empty string`() {
        assertNull(ConfigCoerce.numberFromText("", isIntKind = true))
        assertNull(ConfigCoerce.numberFromText("", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects unparsable text`() {
        assertNull(ConfigCoerce.numberFromText("banana", isIntKind = true))
        assertNull(ConfigCoerce.numberFromText("banana", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects 1e400 instead of returning Infinity`() {
        assertNull(ConfigCoerce.numberFromText("1e400", isIntKind = true))
        assertNull(ConfigCoerce.numberFromText("1e400", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects -1e400 instead of returning -Infinity`() {
        assertNull(ConfigCoerce.numberFromText("-1e400", isIntKind = true))
        assertNull(ConfigCoerce.numberFromText("-1e400", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects NaN text instead of returning Double NaN`() {
        assertNull(ConfigCoerce.numberFromText("NaN", isIntKind = true))
        assertNull(ConfigCoerce.numberFromText("NaN", isIntKind = false))
    }

    @Test
    fun `numberFromText rejects an int-kind value exceeding Int MAX_VALUE instead of saturating`() {
        // Double.toInt() would silently saturate this to Int.MAX_VALUE instead
        // of rejecting it — the whole point of this guard.
        assertNull(ConfigCoerce.numberFromText("99999999999", isIntKind = true))
    }

    @Test
    fun `numberFromText accepts a double-kind value exceeding Int MAX_VALUE, it is not an int field`() {
        assertEquals(99999999999.0, ConfigCoerce.numberFromText("99999999999", isIntKind = false))
    }

    @Test
    fun `numberFromText rounds an integral-valued double for an int-kind field`() {
        assertEquals(42, ConfigCoerce.numberFromText("42.0", isIntKind = true))
    }

    // ---- ConfigCoerce basic type readers ----

    @Test
    fun `bool int double string coerce from their own type and reject mismatches`() {
        assertEquals(true, ConfigCoerce.bool(true))
        assertNull(ConfigCoerce.bool("true"))

        assertEquals(5, ConfigCoerce.int(5))
        assertEquals(5, ConfigCoerce.int(5L))
        assertEquals(5, ConfigCoerce.int(5.0))
        assertNull(ConfigCoerce.int("5"))

        assertEquals(5.0, ConfigCoerce.double(5.0))
        assertEquals(5.0, ConfigCoerce.double(5))
        assertNull(ConfigCoerce.double("5.0"))

        assertEquals("x", ConfigCoerce.string("x"))
        assertNull(ConfigCoerce.string(5))
    }
}
