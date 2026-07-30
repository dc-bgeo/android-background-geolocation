package dev.bgeo.example

import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.Config
import com.bgeo.sdk.NotificationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * Persisted user overrides for the SDK config (Settings screen). A Kotlin
 * port of `react-native/example/src/configStore.ts`'s `applyOverride`/
 * `resetOverrides` ordering; `ios/Example/Sources/ConfigStore.swift` is the
 * same port for iOS — reviewed and fixed in this exact area (see that file's
 * doc comments) after an initial pass got it wrong.
 *
 * INVARIANT: [overrides] only ever contains engine-ACCEPTED keys. Both
 * [setOverride] and [reset] apply to the live engine FIRST and commit to
 * memory/storage ONLY once that call returns normally — never the other way
 * around. Persisting before applying (or applying with the error swallowed)
 * leaves a REJECTED key sitting in storage forever: it gets silently
 * re-applied to the engine on every future boot via [merged], while the
 * caller has no way to know it never actually took, and the log would claim
 * a rejected write "worked". iOS shipped exactly this bug for `setOverride`
 * first, then again for `reset()` in a follow-up review — both ends must
 * keep the same ordering, which is why this file applies the identical
 * pattern to both.
 */
class ConfigStore(
    private val storage: Storage,
    /**
     * Test seam, same shape as `DeviceLink.applyConfig` (see that file's doc
     * comment): `BackgroundGeolocation` is a Kotlin `object` with static
     * members, so it cannot be swapped for a fake the way the SDK's own
     * `Engine` can. Injecting a suspend lambda lets tests assert on exactly
     * the `Config` handed to `setConfig`, and simulate a rejection, without a
     * second protocol/interface.
     */
    private val applyConfig: suspend (Config) -> Unit = { BackgroundGeolocation.setConfig(it) },
) {
    private val _overrides = MutableStateFlow(loadOverrides())

    /** Flat, dot-keyed overrides (e.g. `"notification.priority"`), exactly as the schema declares them. */
    val overrides: StateFlow<Map<String, Any>> = _overrides.asStateFlow()

    private fun loadOverrides(): Map<String, Any> {
        val raw = storage.getString(STORAGE_KEY) ?: return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) continue
            val field = ConfigSchema.field(key) ?: continue
            val coerced = coerceForField(field, json.get(key)) ?: continue
            result[key] = coerced
        }
        return result
    }

    /**
     * Coerces a raw JSON-decoded value (`Integer`/`Long`/`Double`/`Boolean`/
     * `String`, per org.json's own decoding) into the type `field`'s
     * `default` declares. A value that no longer matches its field's kind
     * (e.g. a schema type change since it was written) is dropped rather
     * than surfaced as a wrong-typed override.
     */
    private fun coerceForField(field: ConfigField, raw: Any?): Any? = when (field.default) {
        is Boolean -> ConfigCoerce.bool(raw)
        is Int -> ConfigCoerce.int(raw)
        is Double -> ConfigCoerce.double(raw)
        is String -> ConfigCoerce.string(raw)
        else -> null
    }

    private fun persist(overrides: Map<String, Any>) {
        val json = JSONObject()
        overrides.forEach { (key, value) -> json.put(key, value) }
        storage.putString(STORAGE_KEY, json.toString())
    }

    /**
     * Apply one config key right away (live `setConfig`) and, ONLY once the
     * engine has actually accepted it, persist it. The candidate patch is
     * built from `overrides + (key to value)` — NOT the published
     * [overrides] itself — so an in-flight apply never mutates published
     * state before it is known to have succeeded. Lets a thrown exception
     * propagate uncaught: the caller (`SettingsScreen`) is responsible for
     * surfacing it next to the field that failed and for not logging it as a
     * success.
     */
    suspend fun setOverride(key: String, value: Any) {
        val candidate = _overrides.value + (key to value)
        applyConfig(patchForChangedKey(key, candidate))
        _overrides.value = candidate
        persist(candidate)
    }

    /**
     * Drop all overrides, pushing each previously-overridden key's default
     * back to the live engine FIRST. Same apply-before-persist ordering as
     * [setOverride], for the same reason: a rejected reset must not clear
     * [overrides] while the engine still runs the old values — that would
     * violate the exact invariant [setOverride] exists to guarantee.
     */
    suspend fun reset() {
        val keys = _overrides.value.keys
        if (keys.isEmpty()) return
        applyConfig(resetPatch(keys))
        _overrides.value = emptyMap()
        storage.remove(STORAGE_KEY)
    }

    /**
     * Boot-time merge: [into] with every persisted override layered on top.
     * Keys with no override are left exactly as [into] set them — including
     * nested `notification.*` sub-fields [into] already set that this store
     * has no override for (see [overlayNotification], which deliberately does
     * NOT rebuild the whole notification object the way the live-patch/reset
     * path below does).
     */
    fun merged(into: Config): Config {
        var config = into
        for ((key, value) in _overrides.value) {
            if (key.startsWith(NOTIFICATION_PREFIX)) continue
            config = applyKeyToPatch(key, value, config)
        }
        return config.copy(notification = overlayNotification(config.notification))
    }

    // ---- patch building for live setConfig / reset ------------------------
    //
    // `setConfig` is a PATCH at the engine, but for the `"notification"` key
    // the engine replaces the WHOLE nested map it is given
    // (`BGGeoEngine.kt`'s `notificationMap()` reads `config["notification"]`
    // as one map) — so pushing just `{"notification": {"title": "x"}}` would
    // silently blank every other notification field an earlier call set.
    // Rebuilding the nested object from EVERY schema field of that prefix
    // (override if present, else its default) every time any one of them
    // changes is the same rule `configStore.ts`'s `nestedPatchFor`/
    // `toConfigPatch` encode. This is a DIFFERENT rule from `merged`'s
    // `overlayNotification` above: that one overlays onto an existing `Config`
    // with no live engine state to clobber, so it only needs to touch what is
    // actually overridden.

    private fun patchForChangedKey(key: String, candidateOverrides: Map<String, Any>): Config =
        if (key.startsWith(NOTIFICATION_PREFIX)) {
            Config(notification = fullNotificationPatch(candidateOverrides))
        } else {
            applyKeyToPatch(key, candidateOverrides.getValue(key), Config())
        }

    private fun resetPatch(keys: Set<String>): Config {
        var patch = Config()
        var rebuiltNotification = false
        for (key in keys) {
            if (key.startsWith(NOTIFICATION_PREFIX)) {
                if (rebuiltNotification) continue
                rebuiltNotification = true
                patch = patch.copy(notification = fullNotificationPatch(emptyMap()))
            } else {
                val default = ConfigSchema.defaultFor(key) ?: continue
                patch = applyKeyToPatch(key, default, patch)
            }
        }
        return patch
    }

    /** [merged]'s notification handling: only touches dot-keys this store actually has an override for. */
    private fun overlayNotification(base: NotificationConfig?): NotificationConfig? {
        val overriddenKeys = ConfigSchema.keysWithPrefix(NOTIFICATION_PREFIX).filter { _overrides.value.containsKey(it) }
        if (overriddenKeys.isEmpty()) return base
        var notification = base ?: NotificationConfig()
        for (key in overriddenKeys) {
            notification = assignNotificationField(key.removePrefix(NOTIFICATION_PREFIX), _overrides.value.getValue(key), notification)
        }
        return notification
    }

    /** Live-push/reset's notification handling: rebuilds EVERY notification field from `source` (override if present, else the schema default). */
    private fun fullNotificationPatch(source: Map<String, Any>): NotificationConfig {
        var notification = NotificationConfig()
        for (key in ConfigSchema.keysWithPrefix(NOTIFICATION_PREFIX)) {
            val raw = source[key] ?: ConfigSchema.defaultFor(key) ?: continue
            notification = assignNotificationField(key.removePrefix(NOTIFICATION_PREFIX), raw, notification)
        }
        return notification
    }

    private fun assignNotificationField(sub: String, raw: Any, notification: NotificationConfig): NotificationConfig = when (sub) {
        "title" -> ConfigCoerce.string(raw)?.let { notification.copy(title = it) } ?: notification
        "text" -> ConfigCoerce.string(raw)?.let { notification.copy(text = it) } ?: notification
        "channelId" -> ConfigCoerce.string(raw)?.let { notification.copy(channelId = it) } ?: notification
        "channelName" -> ConfigCoerce.string(raw)?.let { notification.copy(channelName = it) } ?: notification
        "smallIcon" -> ConfigCoerce.string(raw)?.let { notification.copy(smallIcon = it) } ?: notification
        "color" -> ConfigCoerce.string(raw)?.let { notification.copy(color = it) } ?: notification
        "priority" -> ConfigCoerce.int(raw)?.let { notification.copy(priority = it) } ?: notification
        else -> notification
    }

    // ---- key -> Config property --------------------------------------
    //
    // Only writes the property when coercion succeeds — a type-mismatched
    // override must leave whatever `patch`/`config` already had ALONE, not
    // silently drop to null (same clobber hazard `overlayNotification` exists
    // to avoid for `notification.*`, applied here to every scalar key too).
    // Shared by `merged`, the live single-key patch and the reset patch — one
    // `when`, one place that can drift from `ConfigSchema.kt` (guarded by
    // `ConfigStoreTest`/`ConfigSchemaTest`).

    private fun applyKeyToPatch(key: String, raw: Any, patch: Config): Config {
        val s = { ConfigCoerce.string(raw) }
        val i = { ConfigCoerce.int(raw) }
        val d = { ConfigCoerce.double(raw) }
        val b = { ConfigCoerce.bool(raw) }
        return when (key) {
            "locationAuthorizationRequest" -> s()?.let { patch.copy(locationAuthorizationRequest = it) } ?: patch
            "disableLocationAuthorizationAlert" -> b()?.let { patch.copy(disableLocationAuthorizationAlert = it) } ?: patch
            "desiredAccuracy" -> i()?.let { patch.copy(desiredAccuracy = it) } ?: patch
            "distanceFilter" -> d()?.let { patch.copy(distanceFilter = it) } ?: patch
            "stationaryRadius" -> d()?.let { patch.copy(stationaryRadius = it) } ?: patch
            "stationaryDesiredAccuracy" -> s()?.let { patch.copy(stationaryDesiredAccuracy = it) } ?: patch
            "stationaryKeepAlive" -> b()?.let { patch.copy(stationaryKeepAlive = it) } ?: patch
            "stationaryLocationUpdateInterval" -> i()?.let { patch.copy(stationaryLocationUpdateInterval = it) } ?: patch
            "locationUpdateInterval" -> i()?.let { patch.copy(locationUpdateInterval = it) } ?: patch
            "disableLocationFilter" -> b()?.let { patch.copy(disableLocationFilter = it) } ?: patch
            "locationFilterMaxAccuracy" -> d()?.let { patch.copy(locationFilterMaxAccuracy = it) } ?: patch
            "locationFilterMaxSpeed" -> d()?.let { patch.copy(locationFilterMaxSpeed = it) } ?: patch
            "locationFilterPolicy" -> s()?.let { patch.copy(locationFilterPolicy = it) } ?: patch
            "kalmanProfile" -> s()?.let { patch.copy(kalmanProfile = it) } ?: patch
            "odometerAccuracyThreshold" -> d()?.let { patch.copy(odometerAccuracyThreshold = it) } ?: patch
            "stopTimeout" -> i()?.let { patch.copy(stopTimeout = it) } ?: patch
            "motionTriggerDelay" -> i()?.let { patch.copy(motionTriggerDelay = it) } ?: patch
            "minimumActivityRecognitionConfidence" -> i()?.let { patch.copy(minimumActivityRecognitionConfidence = it) } ?: patch
            "triggerActivities" -> s()?.let { patch.copy(triggerActivities = it) } ?: patch
            "activityRecognitionInterval" -> i()?.let { patch.copy(activityRecognitionInterval = it) } ?: patch
            "disableMotionActivityUpdates" -> b()?.let { patch.copy(disableMotionActivityUpdates = it) } ?: patch
            "disableElasticity" -> b()?.let { patch.copy(disableElasticity = it) } ?: patch
            "elasticityMultiplier" -> d()?.let { patch.copy(elasticityMultiplier = it) } ?: patch
            "autoSync" -> b()?.let { patch.copy(autoSync = it) } ?: patch
            "autoSyncThreshold" -> i()?.let { patch.copy(autoSyncThreshold = it) } ?: patch
            "disableAutoSyncOnCellular" -> b()?.let { patch.copy(disableAutoSyncOnCellular = it) } ?: patch
            "batchSync" -> b()?.let { patch.copy(batchSync = it) } ?: patch
            "maxBatchSize" -> i()?.let { patch.copy(maxBatchSize = it) } ?: patch
            "httpTimeoutMs" -> i()?.let { patch.copy(httpTimeoutMs = it) } ?: patch
            "httpRootProperty" -> s()?.let { patch.copy(httpRootProperty = it) } ?: patch
            "method" -> s()?.let { patch.copy(method = it) } ?: patch
            "maxRecordsToPersist" -> i()?.let { patch.copy(maxRecordsToPersist = it) } ?: patch
            "maxDaysToPersist" -> i()?.let { patch.copy(maxDaysToPersist = it) } ?: patch
            "geofenceProximityRadius" -> d()?.let { patch.copy(geofenceProximityRadius = it) } ?: patch
            "maxMonitoredGeofences" -> i()?.let { patch.copy(maxMonitoredGeofences = it) } ?: patch
            "geofenceInitialTriggerEntry" -> b()?.let { patch.copy(geofenceInitialTriggerEntry = it) } ?: patch
            "heartbeatInterval" -> i()?.let { patch.copy(heartbeatInterval = it) } ?: patch
            "stopOnTerminate" -> b()?.let { patch.copy(stopOnTerminate = it) } ?: patch
            "startOnBoot" -> b()?.let { patch.copy(startOnBoot = it) } ?: patch
            "debug" -> b()?.let { patch.copy(debug = it) } ?: patch
            "logLevel" -> i()?.let { patch.copy(logLevel = it) } ?: patch
            "logMaxDays" -> i()?.let { patch.copy(logMaxDays = it) } ?: patch
            "diagnosticExtras" -> b()?.let { patch.copy(diagnosticExtras = it) } ?: patch
            else -> patch // notification.* is handled by the caller; unknown keys are ignored.
        }
    }

    companion object {
        private const val STORAGE_KEY = "bgeo:configOverrides"
        private const val NOTIFICATION_PREFIX = "notification."
    }
}
