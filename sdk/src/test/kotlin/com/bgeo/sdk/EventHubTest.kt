package com.bgeo.sdk

import java.util.concurrent.CountDownLatch
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

    // ---- I2: the flow's channel must not silently drop a live event --------
    //
    // Before `.buffer(Channel.UNLIMITED)`, `flow()`'s callbackFlow used the
    // default capacity (`Channel.BUFFERED` = 64) - exactly the pre-
    // subscription buffer's own cap. A process restart with a full buffer
    // flushes all 64 into the channel the instant a subscriber attaches; the
    // very next live event then finds the channel already full and is
    // dropped by `trySend`, with no signal to anyone.

    @Test
    fun `flow does not drop events once more than sixty-four are queued ahead of a slow collector`() = runBlocking {
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        val received = LinkedBlockingQueue<Int>()
        // Blocks the collector immediately after it picks up the FIRST item,
        // so every subsequent emit sits in the channel undelivered - the
        // exact condition that overflowed the old 64-capacity channel.
        val releaseCollector = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            hub.flow("location").collect {
                if (it.getInt("i") == 0) releaseCollector.await()
                received.add(it.getInt("i"))
            }
        }
        hub.awaitSubscriber("location")

        // 100 live events, all emitted while the collector is blocked on the
        // very first one - far past the old 64-capacity cap.
        for (i in 0 until 100) engine.emit("location", JSONObject().put("i", i))

        releaseCollector.countDown()

        for (i in 0 until 100) {
            assertEquals(i, received.poll(2, TimeUnit.SECONDS))
        }
        job.cancelAndJoin()
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

    @Test
    fun `concurrent emissions from several threads with no subscriber still enforce the buffer cap exactly`() {
        // Unlike the test above (which subscribes FIRST, so every emit takes
        // the has-subscribers branch and only ever READS `subscribers`), this
        // one emits from 8 threads with NO subscriber at all. Every emit takes
        // the no-subscriber branch in `receive`, which does the compound
        // `getOrPut` + size-check + `add` this class's lock exists to protect
        // (see the class doc's synchronisation rationale). If that sequence
        // isn't atomic, 8 threads racing on it can push the buffer past 64,
        // lose an entry to a torn read-modify-write, or throw a
        // ConcurrentModificationException out of the shared ArrayList.
        val engine = FakeEngine()
        val hub = EventHub()
        hub.attach(engine)

        val nextId = AtomicInteger(0)
        val threads = (0 until 8).map {
            Thread { repeat(50) { engine.emit("location", JSONObject().put("id", nextId.getAndIncrement())) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val received = mutableListOf<Int>()
        hub.subscribe("location") { received.add(it.getInt("id")) }

        assertEquals(64, received.size)
        assertEquals("no id should be delivered twice", 64, received.toSet().size)
    }
}
