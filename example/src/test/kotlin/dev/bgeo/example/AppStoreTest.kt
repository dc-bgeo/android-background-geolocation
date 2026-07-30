package dev.bgeo.example

import com.bgeo.sdk.Geofence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStoreTest {

    @Test
    fun `appendLog adds a line to the log stream`() {
        val store = AppStore()
        val line = LogLine(ts = "2026-07-30T00:00:00Z", level = LogLevel.INFO, event = "test")

        store.appendLog(line)

        assertEquals(listOf(line), store.logs.value)
    }

    @Test
    fun `appendLog evicts the oldest lines once the buffer exceeds its cap`() {
        val store = AppStore()
        val lines = (0 until AppStore.MAX_LOGS + 5).map { i ->
            LogLine(ts = "t$i", level = LogLevel.DEBUG, event = "e$i")
        }

        lines.forEach { store.appendLog(it) }

        val observed = store.logs.value
        assertEquals(AppStore.MAX_LOGS, observed.size)
        // Oldest 5 evicted; the buffer keeps the most recent MAX_LOGS entries, in order.
        assertEquals(lines.takeLast(AppStore.MAX_LOGS), observed)
        assertEquals("e5", observed.first().event)
        assertEquals("e${AppStore.MAX_LOGS + 4}", observed.last().event)
    }

    @Test
    fun `clearLogs empties the log stream`() {
        val store = AppStore()
        store.appendLog(LogLine(ts = "t", level = LogLevel.INFO, event = "e"))

        store.clearLogs()

        assertTrue(store.logs.value.isEmpty())
    }

    @Test
    fun `appendPoint adds a point to the track stream`() {
        val store = AppStore()
        val point = Point(latitude = 1.0, longitude = 2.0, timestamp = "t")

        store.appendPoint(point)

        assertEquals(listOf(point), store.points.value)
    }

    @Test
    fun `appendPoint evicts the oldest points once the buffer exceeds its cap`() {
        val store = AppStore()
        val points = (0 until AppStore.MAX_POINTS + 3).map { i ->
            Point(latitude = i.toDouble(), longitude = 0.0, timestamp = "t$i")
        }

        points.forEach { store.appendPoint(it) }

        val observed = store.points.value
        assertEquals(AppStore.MAX_POINTS, observed.size)
        // Oldest 3 evicted; the buffer keeps the most recent MAX_POINTS entries, in order.
        assertEquals(points.takeLast(AppStore.MAX_POINTS), observed)
    }

    @Test
    fun `clearTrack empties the point stream`() {
        val store = AppStore()
        store.appendPoint(Point(latitude = 1.0, longitude = 2.0, timestamp = "t"))

        store.clearTrack()

        assertTrue(store.points.value.isEmpty())
    }

    @Test
    fun `setGeofences replaces the geofence list wholesale`() {
        val store = AppStore()
        val a = Geofence("a", 100.0, 1.0, 2.0, null, null, null, null, null)
        val b = Geofence("b", 200.0, 3.0, 4.0, null, null, null, null, null)

        store.setGeofences(listOf(a))
        assertEquals(listOf(a), store.geofences.value)

        store.setGeofences(listOf(b))
        assertEquals(listOf(b), store.geofences.value)
    }

    @Test
    fun `setLink applies partial updates without touching other fields`() {
        val store = AppStore()

        store.setLink(deviceId = "device-1")
        assertEquals(LinkState(serverUrl = "https://app.bgeo.dev", linked = false, deviceId = "device-1"), store.link.value)

        store.setLink(linked = true)
        assertEquals(LinkState(serverUrl = "https://app.bgeo.dev", linked = true, deviceId = "device-1"), store.link.value)

        store.setLink(serverUrl = "https://custom.example")
        assertEquals(LinkState("https://custom.example", true, "device-1"), store.link.value)
    }

    @Test
    fun `setLink clearDeviceId explicitly nulls the device id, unlike an omitted deviceId`() {
        val store = AppStore()
        store.setLink(deviceId = "device-1")

        store.setLink(clearDeviceId = true)

        assertNull(store.link.value.deviceId)
    }

    @Test
    fun `setStatus applies partial updates without touching other fields`() {
        val store = AppStore()

        store.setStatus(ready = true)
        assertEquals(EngineStatus(ready = true), store.status.value)

        store.setStatus(enabled = true, isMoving = true)
        assertEquals(EngineStatus(ready = true, enabled = true, isMoving = true), store.status.value)

        store.setStatus(batteryLevel = 0.5)
        assertEquals(EngineStatus(ready = true, enabled = true, isMoving = true, batteryLevel = 0.5), store.status.value)
    }
}
