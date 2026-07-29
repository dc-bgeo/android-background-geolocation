package com.bgeo.sdk

import org.json.JSONObject

/**
 * The durable upload queue. Method names and semantics mirror
 * `react-native/src/index.ts:234-278`.
 */

/**
 * Manually drains the upload queue. Snapshots the queue with [getLocations]
 * FIRST, then drains it through the engine's `sync` — resolving with the
 * records that were queued. Reading the queue AFTER draining it would always
 * return an empty list (`react-native/src/index.ts:234-240`).
 */
suspend fun BackgroundGeolocation.sync(): List<Location> {
    val snapshot = getLocations()
    awaitCallback { callback -> engine.sync(callback) }
    return snapshot
}

/**
 * Records currently queued for upload, oldest-first. Unwraps
 * `{"locations": [...]}` (`BGGeoEngine.kt:315`); a malformed record is
 * skipped rather than failing the whole call — this data comes from a
 * durable SQLite queue written by a background service across app versions.
 */
suspend fun BackgroundGeolocation.getLocations(): List<Location> {
    val json = awaitCallback { callback -> engine.getLocations(callback) }
    val array = json?.optJSONArray("locations") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(Location::from) }
}

/** Deletes every queued record. Unwraps `{"count": n}` (`BGGeoEngine.kt:319`). */
suspend fun BackgroundGeolocation.destroyLocations(): Int {
    val json = awaitCallback { callback -> engine.destroyLocations(callback) }
    return json?.intOrNull("count") ?: 0
}

suspend fun BackgroundGeolocation.getCount(): Int = engine.pendingCount()

/** Throws [BGeoException.NotFound] when the engine reports no such uuid was queued. */
suspend fun BackgroundGeolocation.destroyLocation(uuid: String) {
    if (!engine.destroyLocation(uuid)) {
        throw BGeoException.NotFound("No queued location with uuid $uuid")
    }
}

suspend fun BackgroundGeolocation.insertLocation(location: JSONObject) {
    awaitCallback { callback -> engine.insertLocation(location, callback) }
}

/** Tokens the native uploader currently holds — it may have refreshed them while the app was killed. */
data class AuthState(val accessToken: String?, val refreshToken: String?)

suspend fun BackgroundGeolocation.getAuthState(): AuthState {
    val json = engine.authStateMap()
    return AuthState(json.stringOrNull("accessToken"), json.stringOrNull("refreshToken"))
}
