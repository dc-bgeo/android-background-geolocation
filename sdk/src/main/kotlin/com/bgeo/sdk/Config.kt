package com.bgeo.sdk

import org.json.JSONObject

/**
 * Foreground-service notification (Android only). No-op on iOS.
 * `priority` is Transistor NOTIFICATION_PRIORITY_*: -2 MIN (default) .. 2 MAX —
 * it also sets the channel importance, which Android freezes per channelId
 * (change channelId to change it). `smallIcon` = "drawable/name" | "mipmap/name";
 * `color` = "#RRGGBB".
 */
data class NotificationConfig(
    val title: String? = null,
    val text: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val smallIcon: String? = null,
    val color: String? = null,
    val priority: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        title?.let { put("title", it) }
        text?.let { put("text", it) }
        channelId?.let { put("channelId", it) }
        channelName?.let { put("channelName", it) }
        smallIcon?.let { put("smallIcon", it) }
        color?.let { put("color", it) }
        priority?.let { put("priority", it) }
    }
}

/**
 * Native token refresh: on a 401/403 the uploader exchanges `refreshToken`
 * at `refreshUrl` (headers `refreshHeaders`, body `refreshPayload` with the
 * "{refreshToken}" placeholder substituted; default { refresh_token }) so
 * killed-app uploads survive an access-token expiry without a live JS
 * context. Outcomes surface via onAuthorization.
 */
data class AuthorizationConfig(
    val strategy: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val refreshUrl: String? = null,
    val refreshPayload: JSONObject? = null,
    val refreshHeaders: Map<String, String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        strategy?.let { put("strategy", it) }
        accessToken?.let { put("accessToken", it) }
        refreshToken?.let { put("refreshToken", it) }
        refreshUrl?.let { put("refreshUrl", it) }
        refreshPayload?.let { put("refreshPayload", it) }
        refreshHeaders?.let { put("refreshHeaders", JSONObject(it)) }
    }

    companion object {
        /**
         * Set `Config.authorization` to this to clear the whole key — wipes
         * stored tokens/refresh config engine-side. See [Config.CLEAR_STRING];
         * this is the same sentinel, carried on `strategy` because
         * [AuthorizationConfig] has no single scalar of its own to compare.
         */
        val CLEAR = AuthorizationConfig(strategy = Config.CLEAR_STRING)
    }
}

/**
 * Mirrors `interface Config` in `react-native/src/types.ts` (the cross-SDK
 * source of truth for all four front-ends) property-for-property. Guarded
 * against drift by [ConfigDriftTest].
 *
 * `setConfig` is a PATCH, not a replacement: the engine merges whatever it
 * receives into live config. [toJson] therefore OMITS every `null` property —
 * an untouched `Config()` produces an empty [JSONObject] and changes nothing.
 * To express "unset this key" instead, set the property to its `CLEAR_*`
 * sentinel (see [CLEAR_STRING]).
 *
 * The license key is NOT a config option — set it in the app manifest
 * (`<meta-data android:name="com.bgeo.license" android:value="BGEO1..."/>`),
 * read at launch before this API is used. In a RELEASE build a bad key makes
 * `ready()`/`start()` reject with a `LICENSE_*` code; debuggable builds
 * always run unlicensed (evaluation), whatever the key state.
 */
data class Config(
    val locationAuthorizationRequest: String? = null,
    val locationAuthorizationAlert: Map<String, String>? = null,
    /** Suppress the Settings-nudge alert driven by `locationAuthorizationAlert`. Default false. */
    val disableLocationAuthorizationAlert: Boolean? = null,
    /** @unsupported No-op on iOS; Android uses the OS permission rationale flow. */
    val backgroundPermissionRationale: Map<String, String>? = null,
    val desiredAccuracy: Int? = null,
    val distanceFilter: Double? = null,
    /** Bypass the Kalman/accuracy/teleport filter entirely. Default false. */
    val disableLocationFilter: Boolean? = null,
    /** Reject fixes with accuracy worse than this (metres). Default 100. */
    val locationFilterMaxAccuracy: Double? = null,
    /** Teleport rejection: max implied speed between fixes (m/s). Default 60. */
    val locationFilterMaxSpeed: Double? = null,
    /** Filter decision phase: 'Conservative' (default — reject teleport fixes) |
     * 'Adjust' (cap teleports to the kinematic limit instead of dropping) |
     * 'PassThrough' (accuracy gate only; no teleport rejection, no Kalman
     * smoothing). Case-insensitive. Applied when the filter is (re)built at
     * tracking start/stop — not live on setConfig. */
    val locationFilterPolicy: String? = null,
    /** Kalman tuning preset: 'DEFAULT' | 'AGGRESSIVE' (faster response, less lag)
     * | 'CONSERVATIVE' (maximum smoothing). Case-insensitive. Applied when the
     * filter is (re)built at tracking start/stop — not live on setConfig. */
    val kalmanProfile: String? = null,
    /** Fixes with accuracy worse than this (metres) don't advance the odometer.
     * 0 = off (default; tracking/upload unaffected — odometer only). */
    val odometerAccuracyThreshold: Double? = null,
    /** Pin distanceFilter to its base value (no speed-elastic scaling). Default false. */
    val disableElasticity: Boolean? = null,
    /** Speed-elastic distanceFilter scaling intensity. Default 1.0. */
    val elasticityMultiplier: Double? = null,
    /** Accuracy tier for the stationary keep-alive stream: HIGH | BALANCED | LOW. Default BALANCED. */
    val stationaryDesiredAccuracy: String? = null,
    /** @platform android Stationary fused request interval (ms). Default 30000. No-op on iOS. */
    val stationaryLocationUpdateInterval: Int? = null,
    /** CSV of activity names that count as "moving" (e.g. "in_vehicle,on_bicycle,walking,running,on_foot"). */
    val triggerActivities: String? = null,
    /** Min activity-recognition confidence 0-100. Default 75 Android; 50 iOS (coarse 33/66/100 scale). */
    val minimumActivityRecognitionConfidence: Int? = null,
    /** @platform android Activity-recognition poll interval (ms). Default 10000. No-op on iOS. */
    val activityRecognitionInterval: Int? = null,
    /** Ignore motion-activity updates (motion machine falls back to speed + stationary geofence). Default false. */
    val disableMotionActivityUpdates: Boolean? = null,
    val stopTimeout: Int? = null,
    /** @platform ios Show the blue background-location pill under Always auth. false + Always also skips the session engine's CLBackgroundActivitySession to hide the pill (beta — needs field tests). No-op on Android. */
    val showsBackgroundLocationIndicator: Boolean? = null,
    val stationaryRadius: Double? = null,
    /** @platform ios Low-power continuous wake distance; independent of the larger region radius. No-op on Android. */
    val stationaryDistanceFilter: Double? = null,
    /** @platform ios Hold a background task while backgrounded+stationary. No-op on Android. */
    val preventSuspend: Boolean? = null,
    val heartbeatInterval: Int? = null,
    val motionTriggerDelay: Int? = null,
    /** @platform android Fused moving-request interval (ms). Default 1000. No-op on iOS. */
    val locationUpdateInterval: Int? = null,
    /** @unsupported No-op. The Android foreground service is always on while tracking. */
    val foregroundService: Boolean? = null,
    /** @platform android Foreground-service notification. No-op on iOS. See [NotificationConfig]. */
    val notification: NotificationConfig? = null,
    val stopOnTerminate: Boolean? = null,
    val startOnBoot: Boolean? = null,
    /** Plays a one-shot debug sound cue per event (Android/iOS). Does NOT affect tracking. */
    val debug: Boolean? = null,
    /** Native log persistence gate: 0=OFF (default) .. 5=VERBOSE (LOG_LEVEL_* constants). */
    val logLevel: Int? = null,
    /** Days to retain native log rows. Default 3. */
    val logMaxDays: Int? = null,
    /** Absolute URL for native log batch upload ({events:[...]}); unset = local-only. */
    val logUrl: String? = null,
    val maxDaysToPersist: Int? = null,
    val url: String? = null,
    /** HTTP verb for uploads: POST (default) | PUT | PATCH. */
    val method: String? = null,
    val headers: Map<String, String>? = null,
    /** Merged into the request body root alongside the location payload. */
    val params: JSONObject? = null,
    /** Merged into every uploaded record's `extras` (per-call extras win). */
    val extras: JSONObject? = null,
    /** Body key carrying the location(s); default "location". "." merges a single record into the root. */
    val httpRootProperty: String? = null,
    /** Default true. */
    val autoSync: Boolean? = null,
    /** Defer AUTO-sync while on cellular (queue drains on Wi-Fi/ethernet arrival).
     * Explicit sync() always uploads. Default false. */
    val disableAutoSyncOnCellular: Boolean? = null,
    val autoSyncThreshold: Int? = null,
    val batchSync: Boolean? = null,
    val maxBatchSize: Int? = null,
    val httpTimeoutMs: Int? = null,
    val maxRecordsToPersist: Int? = null,
    /** Native token refresh — see [AuthorizationConfig]. */
    val authorization: AuthorizationConfig? = null,
    /** Keep a low-power location request alive while stationary (fast wake source
     * on trip start). Default true; false restores fully-sleep-GPS (slower wake). */
    val stationaryKeepAlive: Boolean? = null,
    /** Upload a compact native diagnostic snapshot in every point's `extras`
     * (counters, app/motion state, manager config) — test devices only. */
    val diagnosticExtras: Boolean? = null,
    /** Session engine (iOS 17+): deliver via CLLocationUpdate.liveUpdates +
     * CLBackgroundActivitySession while moving instead of legacy
     * startUpdatingLocation (which iOS suspends between significant-change wakes).
     * Default true (since 2026-07-23); kept as a remote-config kill-switch.
     * iOS < 17 always uses the legacy path regardless of this flag.
     * Android: silently ignored (stored but unread) — iOS-only key. */
    val useSessionEngine: Boolean? = null,
    /** Proximity slicing for app-facing geofences: only the nearest N within this
     * radius (metres) of the last fix are registered with the OS. Default 1000. */
    val geofenceProximityRadius: Double? = null,
    /** Cap on OS-registered geofences after proximity filtering (platform budget:
     * 19 iOS / 99 Android). <=0 uses the platform budget as-is. Default -1. */
    val maxMonitoredGeofences: Int? = null,
    /** Requests a synthetic ENTER for geofences already-inside on registration
     * (iOS requestStateForRegion / Android INITIAL_TRIGGER_ENTER). Default true. */
    val geofenceInitialTriggerEntry: Boolean? = null,
) {
    /**
     * `setConfig` is a PATCH: this OMITS every `null` property so an
     * untouched `Config()` changes nothing. Properties equal to their
     * `CLEAR_*` sentinel serialise as [JSONObject.NULL] instead, which is how
     * an app expresses "unset this key" (e.g. `Config(url = Config.CLEAR_STRING)`
     * on device unlink) — see [CLEAR_STRING].
     */
    fun toJson(): JSONObject {
        val json = JSONObject()

        locationAuthorizationRequest?.let { json.put("locationAuthorizationRequest", it) }
        locationAuthorizationAlert?.let { json.put("locationAuthorizationAlert", JSONObject(it)) }
        disableLocationAuthorizationAlert?.let { json.put("disableLocationAuthorizationAlert", it) }
        backgroundPermissionRationale?.let { json.put("backgroundPermissionRationale", JSONObject(it)) }
        desiredAccuracy?.let { json.put("desiredAccuracy", it) }
        distanceFilter?.let { json.put("distanceFilter", it) }
        disableLocationFilter?.let { json.put("disableLocationFilter", it) }
        locationFilterMaxAccuracy?.let { json.put("locationFilterMaxAccuracy", it) }
        locationFilterMaxSpeed?.let { json.put("locationFilterMaxSpeed", it) }
        locationFilterPolicy?.let { json.put("locationFilterPolicy", it) }
        kalmanProfile?.let { json.put("kalmanProfile", it) }
        odometerAccuracyThreshold?.let { json.put("odometerAccuracyThreshold", it) }
        disableElasticity?.let { json.put("disableElasticity", it) }
        elasticityMultiplier?.let { json.put("elasticityMultiplier", it) }
        stationaryDesiredAccuracy?.let { json.put("stationaryDesiredAccuracy", it) }
        stationaryLocationUpdateInterval?.let { json.put("stationaryLocationUpdateInterval", it) }
        triggerActivities?.let { json.put("triggerActivities", it) }
        minimumActivityRecognitionConfidence?.let { json.put("minimumActivityRecognitionConfidence", it) }
        activityRecognitionInterval?.let { json.put("activityRecognitionInterval", it) }
        disableMotionActivityUpdates?.let { json.put("disableMotionActivityUpdates", it) }
        stopTimeout?.let { json.put("stopTimeout", it) }
        showsBackgroundLocationIndicator?.let { json.put("showsBackgroundLocationIndicator", it) }
        stationaryRadius?.let { json.put("stationaryRadius", it) }
        stationaryDistanceFilter?.let { json.put("stationaryDistanceFilter", it) }
        preventSuspend?.let { json.put("preventSuspend", it) }
        heartbeatInterval?.let { json.put("heartbeatInterval", it) }
        motionTriggerDelay?.let { json.put("motionTriggerDelay", it) }
        locationUpdateInterval?.let { json.put("locationUpdateInterval", it) }
        foregroundService?.let { json.put("foregroundService", it) }
        notification?.let { json.put("notification", it.toJson()) }
        stopOnTerminate?.let { json.put("stopOnTerminate", it) }
        startOnBoot?.let { json.put("startOnBoot", it) }
        debug?.let { json.put("debug", it) }
        logLevel?.let { json.put("logLevel", it) }
        logMaxDays?.let { json.put("logMaxDays", it) }
        logUrl?.let { json.put("logUrl", if (it == CLEAR_STRING) JSONObject.NULL else it) }
        maxDaysToPersist?.let { json.put("maxDaysToPersist", it) }
        url?.let { json.put("url", if (it == CLEAR_STRING) JSONObject.NULL else it) }
        method?.let { json.put("method", it) }
        headers?.let { json.put("headers", if (it === CLEAR_MAP) JSONObject.NULL else JSONObject(it)) }
        params?.let { json.put("params", if (it === CLEAR_JSON_OBJECT) JSONObject.NULL else it) }
        extras?.let { json.put("extras", if (it === CLEAR_JSON_OBJECT) JSONObject.NULL else it) }
        httpRootProperty?.let { json.put("httpRootProperty", it) }
        autoSync?.let { json.put("autoSync", it) }
        disableAutoSyncOnCellular?.let { json.put("disableAutoSyncOnCellular", it) }
        autoSyncThreshold?.let { json.put("autoSyncThreshold", it) }
        batchSync?.let { json.put("batchSync", it) }
        maxBatchSize?.let { json.put("maxBatchSize", it) }
        httpTimeoutMs?.let { json.put("httpTimeoutMs", it) }
        maxRecordsToPersist?.let { json.put("maxRecordsToPersist", it) }
        authorization?.let {
            json.put("authorization", if (it.strategy == CLEAR_STRING) JSONObject.NULL else it.toJson())
        }
        stationaryKeepAlive?.let { json.put("stationaryKeepAlive", it) }
        diagnosticExtras?.let { json.put("diagnosticExtras", it) }
        useSessionEngine?.let { json.put("useSessionEngine", it) }
        geofenceProximityRadius?.let { json.put("geofenceProximityRadius", it) }
        maxMonitoredGeofences?.let { json.put("maxMonitoredGeofences", it) }
        geofenceInitialTriggerEntry?.let { json.put("geofenceInitialTriggerEntry", it) }

        return json
    }

    companion object {
        /**
         * A property equal to this sentinel serialises as [JSONObject.NULL] in
         * [toJson] instead of being omitted — the only way to express "unset
         * this key" given PATCH semantics (`Config()` with everything `null`
         * must change nothing). Wired on `url`, `logUrl`, `headers`, `params`,
         * `extras`, and (via [AuthorizationConfig.CLEAR]) `authorization` —
         * the keys the Flutter SDK needed a same-shaped workaround for on
         * device unlink (empty strings, since Dart's `Config` cannot express
         * a true clear — see `flutter/lib/src/config.dart` `Config.toMap()`).
         *
         * **Every other property ignores this sentinel.** [toJson] only
         * special-cases the six keys named above; setting e.g.
         * `Config(method = Config.CLEAR_STRING)` does NOT clear `method` —
         * every unwired property serialises as whatever was passed in,
         * unchecked, so the literal sentinel string leaks to the engine as an
         * ordinary value.
         *
         * Example: `Config(url = Config.CLEAR_STRING)` clears the upload `url`.
         */
        const val CLEAR_STRING = "\u0000__BGEO_CLEAR__"

        /** Sentinel for `Map<String, String>?` properties (`headers`). Compared by reference — pass this exact instance. See [CLEAR_STRING]. */
        val CLEAR_MAP: Map<String, String> = mapOf(CLEAR_STRING to CLEAR_STRING)

        /** Sentinel for `JSONObject?` properties (`params`, `extras`). Compared by reference — pass this exact instance. See [CLEAR_STRING]. */
        val CLEAR_JSON_OBJECT: JSONObject = JSONObject().put(CLEAR_STRING, CLEAR_STRING)
    }
}
