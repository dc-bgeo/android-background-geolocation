package dev.bgeo.example

import com.bgeo.sdk.Geofence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Add / edit (an upsert through [Geofences.add], same as RN/iOS) / delete
 * each must hit the engine FIRST and only update [AppStore] after that
 * engine call succeeds. Every "failed engine call" test below would fail
 * against a naive implementation that updates unconditionally: an
 * update-before-engine-call (or a swallowed engine exception) still leaves
 * the store holding the new list, which every assertion here checks is
 * unchanged.
 */
class GeofencesTest {

    private fun fence(id: String = "home", radius: Double = 200.0) =
        Geofence(
            identifier = id, radius = radius, latitude = 52.52, longitude = 13.405,
            notifyOnEntry = true, notifyOnExit = true, notifyOnDwell = false, loiteringDelay = null, extras = null,
        )

    // ---- add ----

    @Test
    fun `add calls the engine first, then updates the store`() = runTest {
        val store = AppStore()
        val engineCalls = mutableListOf<Geofence>()
        val geofences = Geofences(
            store = store,
            addGeofenceCall = { g -> engineCalls += g },
            getGeofencesCall = { listOf(fence()) },
        )

        geofences.add(fence())

        assertEquals(1, engineCalls.size)
        assertEquals(listOf(fence()), store.geofences.value)
    }

    @Test
    fun `a failing engine add leaves the store untouched`() = runTest {
        val store = AppStore()
        val geofences = Geofences(
            store = store,
            addGeofenceCall = { throw RuntimeException("engine rejected geofence") },
            getGeofencesCall = { listOf(fence()) },
        )

        try {
            geofences.add(fence())
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("engine rejected geofence", e.message)
        }

        assertTrue(store.geofences.value.isEmpty())
    }

    // ---- edit (an upsert through add, per Task 5's geometry-keyed change detection) ----

    @Test
    fun `editing an existing geofence (same identifier, new radius) updates the store`() = runTest {
        val store = AppStore()
        val edited = fence(radius = 350.0)
        val geofences = Geofences(
            store = store,
            addGeofenceCall = {},
            getGeofencesCall = { listOf(edited) },
        )

        geofences.add(edited)

        assertEquals(listOf(edited), store.geofences.value)
    }

    @Test
    fun `a failing engine edit leaves the store untouched`() = runTest {
        val store = AppStore()
        store.setGeofences(listOf(fence(radius = 200.0)))
        val geofences = Geofences(
            store = store,
            addGeofenceCall = { throw RuntimeException("engine rejected edit") },
            getGeofencesCall = { listOf(fence(radius = 999.0)) },
        )

        try {
            geofences.add(fence(radius = 999.0))
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            // expected
        }

        // Store keeps the OLD snapshot (radius 200), never the attempted edit.
        assertEquals(200.0, store.geofences.value.single().radius, 0.0)
    }

    // ---- remove ----

    @Test
    fun `remove calls the engine first, then updates the store`() = runTest {
        val store = AppStore()
        store.setGeofences(listOf(fence()))
        val engineCalls = mutableListOf<String>()
        val geofences = Geofences(
            store = store,
            removeGeofenceCall = { id -> engineCalls += id },
            getGeofencesCall = { emptyList() },
        )

        geofences.remove("home")

        assertEquals(listOf("home"), engineCalls)
        assertTrue(store.geofences.value.isEmpty())
    }

    @Test
    fun `a failing engine remove leaves the store untouched`() = runTest {
        val store = AppStore()
        store.setGeofences(listOf(fence()))
        val geofences = Geofences(
            store = store,
            removeGeofenceCall = { throw RuntimeException("engine rejected removal") },
            getGeofencesCall = { emptyList() },
        )

        try {
            geofences.remove("home")
            fail("expected the engine failure to propagate")
        } catch (e: RuntimeException) {
            // expected
        }

        // Store still lists the fence — the device never actually dropped it.
        assertEquals(listOf(fence()), store.geofences.value)
    }

    // ---- refresh ----

    @Test
    fun `refresh updates the store from the engine's set`() = runTest {
        val store = AppStore()
        val geofences = Geofences(store = store, getGeofencesCall = { listOf(fence()) })

        geofences.refresh()

        assertEquals(listOf(fence()), store.geofences.value)
    }
}
