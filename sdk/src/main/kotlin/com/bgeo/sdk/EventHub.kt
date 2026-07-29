package com.bgeo.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

/**
 * A subscription handed back to callers of [EventHub.subscribe] /
 * [EventHub.flow]. [remove] is idempotent — calling it more than once, or
 * after [EventHub.removeAll] has already detached it, is harmless.
 */
public class Subscription internal constructor(private val onRemove: () -> Unit) {
    private val removed = AtomicBoolean(false)

    public fun remove() {
        if (removed.compareAndSet(false, true)) onRemove()
    }
}

/**
 * Claims the engine's single `eventEmitter` slot and fans each event out to
 * every subscriber of that event name.
 *
 * The engine can emit BEFORE any subscriber exists: the process may be
 * restarted (by `BootReceiver` or a geofence broadcast) with the engine
 * running before the app registers listeners. Events that arrive with no
 * subscriber are buffered per event name, capped at 64 (oldest kept), and
 * flushed, in order, to the FIRST subscriber of that name — once, not
 * replayed to every subscriber that ever attaches. The buffer LATCHES shut
 * the first time any subscriber attaches for a given name: it never re-arms,
 * even if every subscriber is later removed, matching the RN bridge's
 * one-shot `_emitterReady` gate (`RNBackgroundGeolocation.mm:83`). Otherwise
 * an app that drops and re-adds its `location` listener would see a burst of
 * up to 64 stale locations replayed as if they just happened.
 *
 * Thread-safety: unlike the iOS twin, the Android engine emits from several
 * threads (`LocationService`, Play Services callbacks, the HTTP executor).
 * The subscriber and buffer maps are guarded by a plain monitor
 * (`synchronized` on a private lock object) rather than a concurrent
 * collection, because every mutation here is a multi-step
 * read-check-then-write (e.g. "append to this name's list, capped at 64")
 * that must be atomic as a unit — a `ConcurrentHashMap` only makes each
 * individual map operation atomic, not the compound sequence. `synchronized`
 * is reentrant on the JVM, but reentrancy is not what makes this safe: the
 * lock is never held while invoking a handler. [receive] and [subscribe] both
 * snapshot the handler list (or the buffered events) under the lock, release
 * it, and only then invoke callbacks — so a handler that re-enters
 * `subscribe`/`Subscription.remove` during delivery cannot deadlock against
 * itself or another thread.
 *
 * Handlers are invoked on the emitting thread (whichever thread called the
 * engine's `eventEmitter`) — EXCEPT a subscriber's pre-subscription buffer
 * flush, which [subscribe] runs inline on the SUBSCRIBING thread, after the
 * lock that guarded the buffer swap has already been released. Because that
 * release-then-dispatch happens in both [receive] and [subscribe], and a
 * live emission can land while a subscription is being set up, the SAME
 * handler can be invoked concurrently on two different threads during that
 * window — most likely exactly once, at subscribe time, which is also the
 * hardest window to reproduce a race in. A handler must therefore be
 * thread-safe on its own (e.g. it must not append to a plain, unsynchronized
 * `ArrayList`); this class does not serialise calls into a single handler.
 * A consumer that wants the main thread must hop there itself, e.g. by
 * posting to a `Handler(Looper.getMainLooper())`, or by collecting [flow] on
 * `Dispatchers.Main`.
 */
internal class EventHub {

    private companion object {
        const val BUFFER_CAP = 64
    }

    private class Entry(val token: Long, val handler: (JSONObject) -> Unit)

    private val lock = Any()
    private val nextToken = AtomicLong(0)

    private val subscribers = HashMap<String, MutableList<Entry>>()
    private val buffers = HashMap<String, MutableList<JSONObject>>()
    private val latchedNames = HashSet<String>()

    /** Claims the engine's `eventEmitter` slot. Call once, before the engine starts emitting. */
    fun attach(engine: Engine) {
        engine.eventEmitter = { name, body -> receive(name, body) }
    }

    private fun receive(name: String, body: JSONObject) {
        val handlers: List<Entry>
        synchronized(lock) {
            val current = subscribers[name]
            if (current.isNullOrEmpty()) {
                if (name !in latchedNames) {
                    val buffered = buffers.getOrPut(name) { mutableListOf() }
                    if (buffered.size < BUFFER_CAP) buffered.add(body)
                }
                return
            }
            // Snapshot under the lock, deliver outside it — see the class doc.
            handlers = current.toList()
        }
        handlers.forEach { it.handler(body) }
    }

    /**
     * Delivers future events for [name] to [handler], then flushes (once) any
     * events buffered for [name] before this subscriber existed — the flush
     * runs inline on the calling (subscribing) thread, not the emitting
     * thread; see the class doc for what that means for thread-safety.
     */
    fun subscribe(name: String, handler: (JSONObject) -> Unit): Subscription {
        val token = nextToken.getAndIncrement()
        val buffered: List<JSONObject>?
        synchronized(lock) {
            subscribers.getOrPut(name) { mutableListOf() }.add(Entry(token, handler))
            latchedNames.add(name)
            buffered = buffers.remove(name)
        }
        buffered?.forEach { handler(it) }
        return Subscription { unsubscribe(name, token) }
    }

    private fun unsubscribe(name: String, token: Long) {
        synchronized(lock) {
            subscribers[name]?.removeAll { it.token == token }
        }
    }

    /**
     * An event-based view over [subscribe] with the same buffering/latching
     * contract, but NOT the same backpressure contract: this delivers via
     * `trySend`, which DROPS the event if the channel's buffer is full,
     * whereas [subscribe]'s handler runs inline on the emitting thread and so
     * blocks the emitter until it returns. A slow collector loses events
     * under [flow]; a slow handler passed to [subscribe] instead stalls
     * whichever engine thread is emitting. Cancelling the collecting
     * coroutine unsubscribes (`awaitClose` runs the removal).
     */
    fun flow(name: String): Flow<JSONObject> = callbackFlow {
        val subscription = subscribe(name) { trySend(it) }
        awaitClose { subscription.remove() }
    }

    /**
     * Detaches every current subscriber, but deliberately does NOT touch the
     * buffers or the latch. Buffers only ever hold entries for names that
     * have never been subscribed to (a latched name is never buffered again —
     * see [receive]), so clearing them here would let an app that calls this
     * before its first `subscribe`/`flow` for a name discard that name's
     * launch-time buffer for good.
     */
    fun removeAll() {
        synchronized(lock) { subscribers.clear() }
    }

    /** Internal test support. */
    internal fun subscriberCount(name: String): Int = synchronized(lock) {
        subscribers[name]?.size ?: 0
    }
}
