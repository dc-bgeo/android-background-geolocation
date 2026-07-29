package com.bgeo.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed models decoded from the engine's `JSONObject` payloads.
 *
 * Field names and optionality follow `react-native/src/types.ts:10-126,279-328`
 * (the cross-SDK source of truth), cross-checked against what
 * `core/android/engine/BGGeoEngine.kt` actually emits — `types.ts` names WHICH
 * fields exist but is not reliable for which are nullable. Failable decoders
 * return `null` when a REQUIRED field is missing or the wrong type; optional
 * fields simply stay `null`. Nothing here throws — this decoder runs on data
 * from a background service in a shipped app and must never crash on a
 * malformed payload.
 */

data class Coords(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double?,
    val altitudeAccuracy: Double?,
    val speed: Double?,
    val speedAccuracy: Double?,
    val heading: Double?,
    val headingAccuracy: Double?,
    val ellipsoidalAltitude: Double?,
) {
    companion object {
        fun from(json: JSONObject): Coords? {
            val latitude = json.doubleOrNull("latitude") ?: return null
            val longitude = json.doubleOrNull("longitude") ?: return null
            val accuracy = json.doubleOrNull("accuracy") ?: return null
            return Coords(
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                altitude = json.doubleOrNull("altitude"),
                altitudeAccuracy = json.doubleOrNull("altitude_accuracy"),
                speed = json.doubleOrNull("speed"),
                speedAccuracy = json.doubleOrNull("speed_accuracy"),
                heading = json.doubleOrNull("heading"),
                headingAccuracy = json.doubleOrNull("heading_accuracy"),
                ellipsoidalAltitude = json.doubleOrNull("ellipsoidal_altitude"),
            )
        }
    }
}

data class MotionActivity(
    val type: ActivityType,
    val confidence: Int,
) {
    companion object {
        fun from(json: JSONObject): MotionActivity? {
            val confidence = json.intOrNull("confidence") ?: return null
            // Unknown/future activity names fall back to UNKNOWN rather than
            // failing the whole decode - the engine may add activity types.
            return MotionActivity(ActivityType.from(json.stringOrNull("type")), confidence)
        }
    }
}

data class Battery(
    val level: Double,
    val isCharging: Boolean,
) {
    companion object {
        fun from(json: JSONObject): Battery? {
            val level = json.doubleOrNull("level") ?: return null
            val isCharging = json.boolOrNull("is_charging") ?: return null
            return Battery(level, isCharging)
        }
    }
}

data class Location(
    val uuid: String,
    val timestamp: String,
    val age: Double?,
    val odometer: Double,
    val coords: Coords,
    val activity: MotionActivity,
    val battery: Battery,
    val isMoving: Boolean,
    val sample: Boolean?,
    val event: String?,
    val extras: JSONObject?,
) {
    companion object {
        fun from(json: JSONObject): Location? {
            val uuid = json.stringOrNull("uuid") ?: return null
            val timestamp = json.stringOrNull("timestamp") ?: return null
            val odometer = json.doubleOrNull("odometer") ?: return null
            val coords = json.objectOrNull("coords")?.let { Coords.from(it) } ?: return null
            val activity = json.objectOrNull("activity")?.let { MotionActivity.from(it) } ?: return null
            val battery = json.objectOrNull("battery")?.let { Battery.from(it) } ?: return null
            return Location(
                uuid = uuid,
                timestamp = timestamp,
                age = json.doubleOrNull("age"),
                odometer = odometer,
                coords = coords,
                activity = activity,
                battery = battery,
                // The engine sends JSONObject.NULL here - not false - while a
                // cold-started session's first fixes are still in the
                // "unconfirmed MOVING" probing window (BGGeoEngine.kt:109-115,
                // is_moving write site :1498) - up to stopTimeout (default 5
                // min) after start(). Coerce to false rather than requiring
                // it, matching Flutter (models.dart:102) and iOS - a required
                // is_moving drops every location in that window.
                isMoving = json.boolOrNull("is_moving") ?: false,
                sample = json.boolOrNull("sample"),
                event = json.stringOrNull("event"),
                extras = json.objectOrNull("extras"),
            )
        }
    }
}

data class Geofence(
    val identifier: String,
    val radius: Double,
    val latitude: Double,
    val longitude: Double,
    val notifyOnEntry: Boolean?,
    val notifyOnExit: Boolean?,
    val notifyOnDwell: Boolean?,
    val loiteringDelay: Double?,
    val extras: JSONObject?,
) {
    /** Round-trips through [from] - used both to send a geofence into the engine and to decode one back out of it. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("identifier", identifier)
        put("radius", radius)
        put("latitude", latitude)
        put("longitude", longitude)
        notifyOnEntry?.let { put("notifyOnEntry", it) }
        notifyOnExit?.let { put("notifyOnExit", it) }
        notifyOnDwell?.let { put("notifyOnDwell", it) }
        loiteringDelay?.let { put("loiteringDelay", it) }
        extras?.let { put("extras", it) }
    }

    companion object {
        fun from(json: JSONObject): Geofence? {
            val identifier = json.stringOrNull("identifier") ?: return null
            val radius = json.doubleOrNull("radius") ?: return null
            val latitude = json.doubleOrNull("latitude") ?: return null
            val longitude = json.doubleOrNull("longitude") ?: return null
            return Geofence(
                identifier = identifier,
                radius = radius,
                latitude = latitude,
                longitude = longitude,
                notifyOnEntry = json.boolOrNull("notifyOnEntry"),
                notifyOnExit = json.boolOrNull("notifyOnExit"),
                notifyOnDwell = json.boolOrNull("notifyOnDwell"),
                loiteringDelay = json.doubleOrNull("loiteringDelay"),
                extras = json.objectOrNull("extras"),
            )
        }

        internal fun listFrom(array: JSONArray): List<Geofence> =
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { from(it) } }
    }
}

data class LogEntry(
    val ts: String,
    val level: Int,
    val src: String,
    val event: String,
    val message: String?,
    val data: String?,
) {
    companion object {
        fun from(json: JSONObject): LogEntry? {
            val ts = json.stringOrNull("ts") ?: return null
            val level = json.intOrNull("level") ?: return null
            val src = json.stringOrNull("src") ?: return null
            val event = json.stringOrNull("event") ?: return null
            return LogEntry(ts, level, src, event, json.stringOrNull("message"), json.stringOrNull("data"))
        }
    }
}

data class ProviderState(
    val status: AuthorizationStatus,
    val enabled: Boolean,
    val gps: Boolean,
    val network: Boolean,
    val accuracyAuthorization: AccuracyAuthorization?,
) {
    companion object {
        fun from(json: JSONObject): ProviderState? = ProviderState(
            status = AuthorizationStatus.from(json.intOrNull("status") ?: -1),
            enabled = json.boolOrNull("enabled") ?: false,
            gps = json.boolOrNull("gps") ?: false,
            network = json.boolOrNull("network") ?: false,
            accuracyAuthorization = json.intOrNull("accuracyAuthorization")
                ?.let { value -> AccuracyAuthorization.values().firstOrNull { it.value == value } },
        )
    }
}

/**
 * `onProviderChange` fires the identical shape `getProviderState()` returns
 * (`types.ts` only names the event `ProviderChangeEvent`) - same fields, same decode.
 */
typealias ProviderChangeEvent = ProviderState

/**
 * The engine reports diagnostic keys alongside `enabled` that vary by engine
 * version. Keeping the raw [JSONObject] - rather than a closed data class
 * with a field per key - means a new engine release can't break this facade;
 * callers reach diagnostics through [get].
 */
data class State(
    val enabled: Boolean,
    val raw: JSONObject,
) {
    operator fun get(key: String): Any? {
        val value = raw.opt(key)
        return if (value == JSONObject.NULL) null else value
    }

    companion object {
        fun from(json: JSONObject): State? = State(json.boolOrNull("enabled") ?: false, json)
    }
}

data class MotionChangeEvent(
    val isMoving: Boolean,
    val location: Location?,
) {
    companion object {
        fun from(json: JSONObject): MotionChangeEvent? {
            val isMoving = json.boolOrNull("isMoving") ?: return null
            // `location` is absent on Android for the first motionchange of a
            // tracking session. objectOrNull returns null for both an absent
            // key and JSONObject.NULL, so this never crashes on either shape.
            val location = json.objectOrNull("location")?.let { Location.from(it) }
            return MotionChangeEvent(isMoving, location)
        }
    }
}

enum class GeofenceAction(val wire: String) {
    ENTER("ENTER"),
    EXIT("EXIT"),
    DWELL("DWELL"),
    ;

    companion object {
        fun from(wire: String?): GeofenceAction? = values().firstOrNull { it.wire == wire }
    }
}

data class GeofenceEvent(
    val identifier: String,
    val action: GeofenceAction,
    val location: Location,
    val extras: JSONObject?,
) {
    companion object {
        fun from(json: JSONObject): GeofenceEvent? {
            val identifier = json.stringOrNull("identifier") ?: return null
            val action = GeofenceAction.from(json.stringOrNull("action")) ?: return null
            val location = json.objectOrNull("location")?.let { Location.from(it) } ?: return null
            return GeofenceEvent(identifier, action, location, json.objectOrNull("extras"))
        }
    }
}

data class GeofencesChangeEvent(
    val on: List<Geofence>,
    val off: List<Geofence>,
) {
    companion object {
        fun from(json: JSONObject): GeofencesChangeEvent? {
            val on = json.optJSONArray("on") ?: return null
            val off = json.optJSONArray("off") ?: return null
            return GeofencesChangeEvent(Geofence.listFrom(on), Geofence.listFrom(off))
        }
    }
}

/** Fate of one location-sync HTTP request (one event per request). */
data class HttpEvent(
    val success: Boolean,
    val status: Int,
    val responseText: String,
) {
    companion object {
        fun from(json: JSONObject): HttpEvent? {
            val success = json.boolOrNull("success") ?: return null
            val status = json.intOrNull("status") ?: return null
            val responseText = json.stringOrNull("responseText") ?: return null
            return HttpEvent(success, status, responseText)
        }
    }
}

data class ConnectivityChangeEvent(
    val connected: Boolean,
) {
    companion object {
        fun from(json: JSONObject): ConnectivityChangeEvent? {
            val connected = json.boolOrNull("connected") ?: return null
            return ConnectivityChangeEvent(connected)
        }
    }
}

data class HeartbeatEvent(val raw: JSONObject) {
    companion object {
        fun from(json: JSONObject): HeartbeatEvent = HeartbeatEvent(json)
    }
}
