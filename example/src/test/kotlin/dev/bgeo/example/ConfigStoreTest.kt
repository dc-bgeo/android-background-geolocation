package dev.bgeo.example

import com.bgeo.sdk.Config
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records every `Config` handed to `applyConfig`; throws instead when armed, to simulate the engine rejecting a `setConfig`. */
private class RecordingApplyConfig {
    val applied = mutableListOf<Config>()
    private var shouldThrow = false

    fun rejectNextCall() {
        shouldThrow = true
    }

    val fn: suspend (Config) -> Unit = { config ->
        if (shouldThrow) {
            shouldThrow = false
            throw RuntimeException("engine rejected config")
        }
        applied.add(config)
    }
}

class ConfigStoreTest {

    @Test
    fun `setOverride applies before persisting, and a fresh store over the same storage sees it`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)

        store.setOverride("distanceFilter", 25.0)

        assertEquals(25.0, store.overrides.value["distanceFilter"])
        assertEquals(1, recorder.applied.size)
        assertEquals(25.0, recorder.applied[0].distanceFilter)

        val fresh = ConfigStore(storage, recorder.fn)
        assertEquals(25.0, fresh.overrides.value["distanceFilter"])
    }

    // ---- the load-bearing test: a rejected setConfig must leave nothing in
    // memory AND nothing in storage. Proven by reading back through a FRESH
    // ConfigStore over the same Storage, so a persist-then-apply ordering
    // bug would fail this even though the exception still propagates
    // correctly either way. ----

    @Test
    fun `a setOverride the engine rejects leaves nothing in memory and nothing in storage`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        recorder.rejectNextCall()
        val store = ConfigStore(storage, recorder.fn)

        try {
            store.setOverride("distanceFilter", 25.0)
            fail("expected the rejection to propagate")
        } catch (e: RuntimeException) {
            assertEquals("engine rejected config", e.message)
        }

        assertTrue(store.overrides.value.isEmpty())
        assertTrue(recorder.applied.isEmpty())

        // Read back through a SEPARATE ConfigStore instance over the same
        // Storage: this is what actually falsifies a persist-then-apply bug
        // (the in-memory assertion above would pass even if persist() ran
        // first and the exception still propagated afterward).
        val fresh = ConfigStore(storage, recorder.fn)
        assertTrue(fresh.overrides.value.isEmpty())
    }

    @Test
    fun `reset applies before clearing storage, and a fresh store over the same storage sees it cleared`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)
        store.setOverride("distanceFilter", 25.0)

        store.reset()

        assertTrue(store.overrides.value.isEmpty())
        val fresh = ConfigStore(storage, recorder.fn)
        assertTrue(fresh.overrides.value.isEmpty())
    }

    // ---- the same load-bearing test for reset(): iOS left this path unfixed
    // in its first pass after fixing setOverride, and a reviewer called that
    // indefensible since it violates the same invariant. ----

    @Test
    fun `a reset the engine rejects does not clear stored overrides`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)
        store.setOverride("distanceFilter", 25.0)

        recorder.rejectNextCall()
        try {
            store.reset()
            fail("expected the rejection to propagate")
        } catch (e: RuntimeException) {
            assertEquals("engine rejected config", e.message)
        }

        assertEquals(25.0, store.overrides.value["distanceFilter"])

        // Fresh store over the same Storage must ALSO still see the override —
        // proves reset() didn't clear storage ahead of the (rejected) apply.
        val fresh = ConfigStore(storage, recorder.fn)
        assertEquals(25.0, fresh.overrides.value["distanceFilter"])
    }

    @Test
    fun `setOverride on a notification key rebuilds the whole nested object from schema defaults`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)

        store.setOverride("notification.title", "Custom title")

        val notification = recorder.applied.single().notification!!
        assertEquals("Custom title", notification.title)
        // Every other notification field is filled from its schema default,
        // not left null — a `setConfig` on "notification" replaces the WHOLE
        // nested object at the engine, so a partial patch would blank these.
        assertEquals("Location tracking active", notification.text)
        assertEquals("bgeo_location_min", notification.channelId)
        assertEquals("Location", notification.channelName)
        assertEquals(-2, notification.priority)
    }

    @Test
    fun `reset pushes every overridden key's default back to the engine exactly once per prefix`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)
        store.setOverride("distanceFilter", 25.0)
        store.setOverride("notification.title", "Custom")
        store.setOverride("notification.text", "Custom text")
        recorder.applied.clear()

        store.reset()

        val patch = recorder.applied.single()
        assertEquals(10.0, patch.distanceFilter) // schema default
        assertEquals("Location", patch.notification?.title) // schema default, not "Custom"
        assertEquals("Location tracking active", patch.notification?.text)
    }

    @Test
    fun `merged overlays overrides onto a base config without touching untouched notification fields`() = runTest {
        val storage = InMemoryStorage()
        val recorder = RecordingApplyConfig()
        val store = ConfigStore(storage, recorder.fn)
        store.setOverride("distanceFilter", 25.0)
        store.setOverride("notification.title", "Custom")

        val base = Config(
            distanceFilter = 10.0,
            stopTimeout = 7,
            notification = com.bgeo.sdk.NotificationConfig(text = "base text", channelId = "base-channel"),
        )
        val merged = store.merged(base)

        assertEquals(25.0, merged.distanceFilter)
        assertEquals(7, merged.stopTimeout) // untouched key, left exactly as base set it
        assertEquals("Custom", merged.notification?.title)
        assertEquals("base text", merged.notification?.text) // untouched notification field, left as base set it
        assertEquals("base-channel", merged.notification?.channelId)
    }

    @Test
    fun `a stale JSON blob with an unknown key is dropped rather than crashing on load`() = runTest {
        val storage = InMemoryStorage()
        storage.putString("bgeo:configOverrides", """{"distanceFilter":25.0,"notAKeyAnymore":1}""")

        val store = ConfigStore(storage, RecordingApplyConfig().fn)

        assertEquals(mapOf("distanceFilter" to 25.0), store.overrides.value)
    }
}
