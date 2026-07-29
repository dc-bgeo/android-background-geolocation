package com.bgeo.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONArray

/**
 * App-facing geofences. Method and event names mirror
 * `react-native/src/index.ts:370-399`.
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
    awaitCallback { callback -> engine.addGeofences(array, callback) }
}

suspend fun BackgroundGeolocation.removeGeofence(identifier: String) {
    awaitCallback { callback -> engine.removeGeofence(identifier, callback) }
}

suspend fun BackgroundGeolocation.removeGeofences() {
    awaitCallback { callback -> engine.removeGeofences(callback) }
}

/**
 * Unwraps `{"geofences": [...]}` (`BGGeoEngine.kt:1036-1038`); a malformed
 * record is skipped rather than failing the whole call.
 */
suspend fun BackgroundGeolocation.getGeofences(): List<Geofence> {
    val json = awaitCallback { callback -> engine.getGeofences(callback) }
    val array = json?.optJSONArray("geofences") ?: return emptyList()
    return Geofence.listFrom(array)
}

/** Unwraps `{"exists": bool}` (`BGGeoEngine.kt:1040-1042`). */
suspend fun BackgroundGeolocation.geofenceExists(identifier: String): Boolean {
    val json = awaitCallback { callback -> engine.geofenceExists(identifier, callback) }
    return json?.boolOrNull("exists") ?: false
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
