package com.bgeo.sdk

import com.bgeo.BGGeoCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * Bridges the engine's single-shot [BGGeoCallback] contract into a coroutine.
 *
 * The [AtomicBoolean] guard is a correctness requirement, not defensive
 * clutter: resuming a [kotlinx.coroutines.CancellableContinuation] twice
 * throws `IllegalStateException` and takes down the caller's coroutine, and
 * the engine's callbacks arrive from several threads (`LocationService`, Play
 * Services callbacks, the HTTP executor) — a callback that fires twice must
 * be survivable, with only the first outcome winning.
 */
internal suspend fun awaitCallback(block: (BGGeoCallback) -> Unit): JSONObject? =
    suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        block(object : BGGeoCallback {
            override fun success(result: JSONObject?) {
                if (resumed.compareAndSet(false, true)) continuation.resume(result)
            }
            override fun error(code: String, message: String) {
                if (resumed.compareAndSet(false, true)) {
                    continuation.resumeWithException(BGeoException.from(code, message))
                }
            }
        })
    }
