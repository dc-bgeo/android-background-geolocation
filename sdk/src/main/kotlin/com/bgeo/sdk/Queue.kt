package com.bgeo.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The durable upload queue. Method names and semantics mirror
 * `react-native/src/index.ts:234-278`.
 *
 * Every member here hops to [Dispatchers.IO]: the engine resolves its
 * `BGGeoCallback`-shaped calls synchronously on the calling thread, and the
 * work underneath is direct SQLite (`BGGeoDb.kt`/`BGGeoHttpStore.kt`) —
 * without the hop, `suspend` here is only a promise on paper; a caller on
 * `Dispatchers.Main.immediate` would block the UI thread reading/writing the
 * queue.
 */

/**
 * Manually drains the upload queue. Snapshots the queue with [getLocations]
 * FIRST, then drains it through the engine's `sync` — resolving with the
 * records that were queued. Reading the queue AFTER draining it would always
 * return an empty list (`react-native/src/index.ts:234-240`).
 */
suspend fun BackgroundGeolocation.sync(): List<Location> = withContext(Dispatchers.IO) {
    val snapshot = getLocations()
    awaitCallback { callback -> engine.sync(callback) }
    snapshot
}

/**
 * Records currently queued for upload, oldest-first. Unwraps
 * `{"locations": [...]}` (`BGGeoEngine.kt:315`); a malformed record is
 * skipped rather than failing the whole call — this data comes from a
 * durable SQLite queue written by a background service across app versions.
 */
suspend fun BackgroundGeolocation.getLocations(): List<Location> = withContext(Dispatchers.IO) {
    val json = awaitCallback { callback -> engine.getLocations(callback) }
    val array = json?.optJSONArray("locations") ?: return@withContext emptyList()
    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(Location::from) }
}

/** Deletes every queued record. Unwraps `{"count": n}` (`BGGeoEngine.kt:319`). */
suspend fun BackgroundGeolocation.destroyLocations(): Int = withContext(Dispatchers.IO) {
    val json = awaitCallback { callback -> engine.destroyLocations(callback) }
    json?.intOrNull("count") ?: 0
}

suspend fun BackgroundGeolocation.getCount(): Int = withContext(Dispatchers.IO) { engine.pendingCount() }

/** Throws [BGeoException.NotFound] when the engine reports no such uuid was queued. */
suspend fun BackgroundGeolocation.destroyLocation(uuid: String) {
    withContext(Dispatchers.IO) {
        if (!engine.destroyLocation(uuid)) {
            throw BGeoException.NotFound("No queued location with uuid $uuid")
        }
    }
}

suspend fun BackgroundGeolocation.insertLocation(location: JSONObject) {
    withContext(Dispatchers.IO) {
        awaitCallback { callback -> engine.insertLocation(location, callback) }
    }
}

/** Tokens the native uploader currently holds — it may have refreshed them while the app was killed. */
data class AuthState(val accessToken: String?, val refreshToken: String?)

suspend fun BackgroundGeolocation.getAuthState(): AuthState = withContext(Dispatchers.IO) {
    val json = engine.authStateMap()
    AuthState(json.stringOrNull("accessToken"), json.stringOrNull("refreshToken"))
}
