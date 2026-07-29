package com.bgeo.sdk

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EventHubTest {

    /**
     * Bounded poll on [EventHub.subscriberCount] — a deterministic proxy for
     * "the collector has registered" that replaces the flaky `Task.yield()`
     * proxy the iOS phase used (it flaked ~2 runs in 10; nothing guarantees a
     * yield actually hands off to the collecting coroutine).
     */
    private fun EventHub.awaitSubscriber(name: String, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (subscriberCount(name) == 0) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for a subscriber to \"$name\"")
            }
            Thread.sleep(5)
        }
    }

    @Test
    fun `fans one engine event out to every subscriber`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        val first = mutableListOf<JSONObject>()
        val second = mutableListOf<JSONObject>()
        hub.subscribe("location") { first.add(it) }
        hub.subscribe("location") { second.add(it) }

        engine.emit("location", JSONObject().put("uuid", "a"))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals("a", first[0].getString("uuid"))
    }

    @Test
    fun `subscribers only receive their own event name`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        var locations = 0
        var heartbeats = 0
        hub.subscribe("location") { locations++ }
        hub.subscribe("heartbeat") { heartbeats++ }

        engine.emit("heartbeat", JSONObject())

        assertEquals(0, locations)
        assertEquals(1, heartbeats)
    }

    @Test
    fun `events emitted before any subscriber are buffered and replayed in order`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        engine.emit("location", JSONObject().put("uuid", "first"))
        engine.emit("location", JSONObject().put("uuid", "second"))

        val received = mutableListOf<String>()
        hub.subscribe("location") { received.add(it.getString("uuid")) }

        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun `buffer is capped at sixty-four and keeps the oldest`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        for (i in 0 until 100) engine.emit("location", JSONObject().put("i", i))

        val received = mutableListOf<Int>()
        hub.subscribe("location") { received.add(it.getInt("i")) }

        assertEquals(64, received.size)
        assertEquals(0, received.first())
        assertEquals(63, received.last())
    }

    @Test
    fun `buffer is drained so a second subscriber does not replay it`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)
        engine.emit("location", JSONObject().put("uuid", "buffered"))

        var firstReceived = 0
        var secondReceived = 0
        hub.subscribe("location") { firstReceived++ }
        hub.subscribe("location") { secondReceived++ }

        assertEquals(1, firstReceived)
        assertEquals(0, secondReceived)
    }

    @Test
    fun `removing a subscription stops delivery`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        var count = 0
        val subscription = hub.subscribe("location") { count++ }
        engine.emit("location", JSONObject())
        subscription.remove()
        engine.emit("location", JSONObject())

        assertEquals(1, count)
    }

    @Test
    fun `removing twice is harmless`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)
        val subscription = hub.subscribe("location") { }
        subscription.remove()
        subscription.remove()
        engine.emit("location", JSONObject())
    }

    @Test
    fun `removeAll detaches every subscriber`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)
        var count = 0
        hub.subscribe("location") { count++ }
        hub.subscribe("heartbeat") { count++ }
        hub.removeAll()
        engine.emit("location", JSONObject())
        engine.emit("heartbeat", JSONObject())
        assertEquals(0, count)
    }

    @Test
    fun `removeAll does not discard the buffer for a name that was never subscribed to`() {
        // Regression guard mirroring the iOS phase: removeAll() must not
        // clear pre-subscribe buffers, or an app calling removeListeners()
        // before its first subscribe would lose the launch-time buffer the
        // latch exists to protect.
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        engine.emit("location", JSONObject().put("uuid", "buffered-before-removeAll"))
        hub.removeAll()

        val received = mutableListOf<String>()
        hub.subscribe("location") { received.add(it.getString("uuid")) }

        assertEquals(listOf("buffered-before-removeAll"), received)
    }

    @Test
    fun `buffer does not re-arm once latched even after every subscriber is removed`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        var firstReceived = 0
        val subscription = hub.subscribe("location") { firstReceived++ }
        subscription.remove()

        engine.emit("location", JSONObject().put("uuid", "late"))

        var secondReceived = 0
        hub.subscribe("location") { secondReceived++ }

        assertEquals(0, firstReceived)
        assertEquals(0, secondReceived)
    }

    @Test
    fun `flow delivers events to a collector`() = runBlocking {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        val received = LinkedBlockingQueue<String>()
        val job = launch(Dispatchers.Default) {
            hub.flow("location").collect { received.add(it.getString("uuid")) }
        }
        hub.awaitSubscriber("location")
        engine.emit("location", JSONObject().put("uuid", "streamed"))

        assertEquals("streamed", received.poll(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling a flow collection unsubscribes`() = runBlocking {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        val job = launch(Dispatchers.Default) {
            hub.flow("location").collect { }
        }
        hub.awaitSubscriber("location")
        assertEquals(1, hub.subscriberCount("location"))

        // Deterministic: join() only returns once the cancelled coroutine has
        // fully completed, which for a callbackFlow includes running
        // awaitClose's cleanup (the unsubscribe). No delay()/yield() needed.
        job.cancelAndJoin()

        assertEquals(0, hub.subscriberCount("location"))
        engine.emit("location", JSONObject())
    }

    @Test
    fun `concurrent emissions from several threads are all delivered`() {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)
        val received = AtomicInteger()
        hub.subscribe("location") { received.incrementAndGet() }

        val threads = (0 until 8).map { thread ->
            Thread { repeat(100) { engine.emit("location", JSONObject().put("t", thread)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(800, received.get())
    }
}
