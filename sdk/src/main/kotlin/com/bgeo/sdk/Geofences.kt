package com.bgeo.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * App-facing geofences. Method and event names mirror
 * `react-native/src/index.ts:370-399`.
 *
 * The mutating/reading members below hop to `Dispatchers.IO`, same reasoning
 * as `Queue.kt`'s: they resolve synchronously on the calling thread, backed
 * by `GeofenceStore`'s direct SQLite (`BGGeoDb.kt`'s `geofences` table).
 */

/**
 * Delegates to [addGeofences] with a one-element list rather than
 * duplicating the license-gated add logic (`BGGeoEngine.kt:1010-1023`).
 */
suspend fun BackgroundGeolocation.addGeofence(geofence: Geofence) = addGeofences(listOf(geofence))

/**
 * The engine gates the licence here: an error can be a `LICENSE_*` code as
 * well as `INVALID_GEOFENCE` (`BGGeoEngine.kt:1010-1023`) — [awaitCallback]
 * turns either into the matching typed [BGeoException], code intact.
 */
suspend fun BackgroundGeolocation.addGeofences(geofences: List<Geofence>) {
    val array = JSONArray().apply { geofences.forEach { put(it.toJson()) } }
    withContext(Dispatchers.IO) {
        awaitCallback { callback -> engine.addGeofences(array, callback) }
    }
}

suspend fun BackgroundGeolocation.removeGeofence(identifier: String) {
    withContext(Dispatchers.IO) {
        awaitCallback { callback -> engine.removeGeofence(identifier, callback) }
    }
}

suspend fun BackgroundGeolocation.removeGeofences() {
    withContext(Dispatchers.IO) {
        awaitCallback { callback -> engine.removeGeofences(callback) }
    }
}

/**
 * Unwraps `{"geofences": [...]}` (`BGGeoEngine.kt:1036-1038`); a malformed
 * record is skipped rather than failing the whole call.
 */
suspend fun BackgroundGeolocation.getGeofences(): List<Geofence> = withContext(Dispatchers.IO) {
    val json = awaitCallback { callback -> engine.getGeofences(callback) }
    val array = json?.optJSONArray("geofences") ?: return@withContext emptyList()
    Geofence.listFrom(array)
}

/** Unwraps `{"exists": bool}` (`BGGeoEngine.kt:1040-1042`). */
suspend fun BackgroundGeolocation.geofenceExists(identifier: String): Boolean = withContext(Dispatchers.IO) {
    val json = awaitCallback { callback -> engine.geofenceExists(identifier, callback) }
    json?.boolOrNull("exists") ?: false
}

fun BackgroundGeolocation.onGeofence(handler: (GeofenceEvent) -> Unit): Subscription =
    hub.subscribe("geofence") { json -> GeofenceEvent.from(json)?.let(handler) }

fun BackgroundGeolocation.onGeofencesChange(handler: (GeofencesChangeEvent) -> Unit): Subscription =
    hub.subscribe("geofenceschange") { json -> GeofencesChangeEvent.from(json)?.let(handler) }

/** See `BackgroundGeolocation.locations` — each access mints a new subscription. */
val BackgroundGeolocation.geofenceEvents: Flow<GeofenceEvent>
    get() = hub.flow("geofence").mapNotNull(GeofenceEvent::from)

/** See `BackgroundGeolocation.locations` — each access mints a new subscription. */
val BackgroundGeolocation.geofenceChanges: Flow<GeofencesChangeEvent>
    get() = hub.flow("geofenceschange").mapNotNull(GeofencesChangeEvent::from)
