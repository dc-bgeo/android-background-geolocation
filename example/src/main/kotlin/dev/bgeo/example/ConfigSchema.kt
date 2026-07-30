package dev.bgeo.example

/**
 * Declarative schema of the SDK's working `Config` keys (see
 * `sdk/src/main/kotlin/com/bgeo/sdk/Config.kt`) — single source for the
 * Settings screen UI and for reset-to-defaults. A Kotlin port of
 * `react-native/example/src/configSchema.ts` in shape; `ios/Example/Sources/
 * ConfigSchema.swift` is the same port for iOS, reviewed and corrected in
 * this exact area (see that file's header for the RN/Flutter defaults it had
 * to fix and why).
 *
 * INVARIANT: every `default` below is the value the engine is ACTUALLY
 * RUNNING when the user has not overridden that key — i.e.
 * `ConfigSchema.defaultFor(key)` must equal what `ready()` boots with. For
 * the six keys `ExampleApp.kt`'s `baseConfig` sets, that is baseConfig's
 * value; for every other key it is the LITERAL fallback the ANDROID ENGINE
 * (`core/android/engine/src/main/java/com/bgeo/BGGeoEngine.kt`,
 * `BGGeoHttpStore.kt`) uses when the key is absent from `setConfig`/`ready()`
 * — verified by reading the engine source directly (grep + line numbers cited
 * per field below), NOT copied from another console. `ExampleAppTest` pins
 * the baseConfig half of that invariant; without it the Settings screen
 * states a value the engine is not running, and Reset becomes a behaviour
 * change instead of a revert. `core/.superpowers/sdd/
 * 2026-07-29-native-sdks-phase3-ios-example/rn-flutter-parity-report.md`
 * records the same reconciliation for RN/Flutter; where it agrees with what
 * this file found in the Android engine, the report is cited too. Citations
 * live in CODE COMMENTS, never in the user-facing `hint` string — iOS's
 * first pass shipped repo paths into its Settings UI and had to undo it.
 *
 * This is a single-platform (Android-only) console, so unlike the RN/iOS/
 * Flutter schemas there is no `platform:` tag on `ConfigField`: every field
 * kept here is one the Android engine actually reads. Keys that are genuine
 * no-ops on Android are excluded outright instead, same reasoning the other
 * consoles use for their own no-op exclusions:
 *  - `foregroundService`, `backgroundPermissionRationale`: documented
 *    `@unsupported` directly on `Config`.
 *  - `stationaryDistanceFilter`, `showsBackgroundLocationIndicator`,
 *    `preventSuspend`, `useSessionEngine`: documented `@platform ios` on
 *    `Config` and independently confirmed here — zero occurrences anywhere
 *    under `core/android/engine`.
 *  - `locationAuthorizationAlert`, `headers`, `params`, `extras`: nested
 *    dictionaries; `ConfigField.type` has no editor for a raw map.
 *  - `url`, `logUrl`, `authorization`: owned exclusively by `DeviceLink`
 *    (Task 3) — editing them independently here would desync the linked
 *    server relationship.
 *  - `diagnosticExtras` is the one exception KEPT despite not being read by
 *    the Android engine yet (confirmed: no occurrence anywhere under
 *    `core/android/engine`) — `Config.kt` does not tag it `@platform ios`
 *    (unlike the four excluded above), so it is treated as an accepted,
 *    stored-but-currently-inert key rather than a documented no-op.
 */

enum class ConfigFieldType { BOOL, NUMBER, ENUM, STRING }

/** `value` is `Int`, `Double`, `Boolean` or `String` — whichever matches the owning field's `default` kind. */
data class ConfigFieldOption(val label: String, val value: Any)

data class ConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType,
    val options: List<ConfigFieldOption> = emptyList(),
    /** The literal Android engine default (see file header). `Int`, `Double`, `Boolean` or `String`. */
    val default: Any,
    val unit: String? = null,
    val hint: String? = null,
)

data class ConfigSection(val title: String, val fields: List<ConfigField>)

object ConfigSchema {

    private val accuracyOptions = listOf(
        ConfigFieldOption("NAV", -2),
        ConfigFieldOption("HIGH", -1),
        ConfigFieldOption("MED", 10),
        ConfigFieldOption("LOW", 100),
        ConfigFieldOption("V.LOW", 1000),
    )

    private val stationaryAccuracyOptions = listOf(
        ConfigFieldOption("HIGH", "HIGH"),
        ConfigFieldOption("BAL", "BALANCED"),
        ConfigFieldOption("LOW", "LOW"),
    )

    val sections: List<ConfigSection> = listOf(
        ConfigSection(
            title = "Permissions",
            fields = listOf(
                // Engine default "Always": a missing/unrecognized value falls
                // through to `true` (BGGeoEngine.kt:353,
                // `?.equals("Always", ignoreCase = true) ?: true`).
                ConfigField(
                    key = "locationAuthorizationRequest",
                    label = "Authorization request",
                    type = ConfigFieldType.ENUM,
                    options = listOf(ConfigFieldOption("Always", "Always"), ConfigFieldOption("When In Use", "WhenInUse")),
                    default = "Always",
                ),
                // BGGeoEngine.kt:372 — `?: false`.
                ConfigField(
                    key = "disableLocationAuthorizationAlert",
                    label = "Disable auth alert",
                    type = ConfigFieldType.BOOL,
                    default = false,
                    hint = "suppresses the Settings-nudge alert",
                ),
            ),
        ),
        ConfigSection(
            title = "Geolocation",
            fields = listOf(
                // A missing desiredAccuracy maps to PRIORITY_HIGH_ACCURACY
                // (BGGeoEngine.kt:1571-1573: `desiredAccuracy == null -> HIGH`),
                // i.e. behaves like -1/HIGH.
                ConfigField(
                    key = "desiredAccuracy", label = "Desired accuracy", type = ConfigFieldType.ENUM,
                    options = accuracyOptions, default = -1,
                ),
                // BGGeoEngine.kt:722 — `?: 10.0`.
                ConfigField(key = "distanceFilter", label = "Distance filter", type = ConfigFieldType.NUMBER, default = 10.0, unit = "m"),
                // CONFIRMED — engine default 200 (BGGeoEngine.kt:636: `?: 200f`),
                // matching the RN/Flutter/iOS reconciliation in
                // core/.superpowers/sdd/2026-07-29-native-sdks-phase3-ios-example/
                // rn-flutter-parity-report.md. NOT the stale 25 those consoles
                // shipped with before their own fix.
                ConfigField(key = "stationaryRadius", label = "Stationary radius", type = ConfigFieldType.NUMBER, default = 200.0, unit = "m"),
                // BGGeoEngine.kt:1591-1592 — `?: AccuracyLevel.BALANCED`.
                ConfigField(
                    key = "stationaryDesiredAccuracy", label = "Stationary accuracy", type = ConfigFieldType.ENUM,
                    options = stationaryAccuracyOptions, default = "BALANCED",
                ),
                // BGGeoEngine.kt:366 — `?: true`.
                ConfigField(key = "stationaryKeepAlive", label = "Stationary keep-alive", type = ConfigFieldType.BOOL, default = true),
                // Android-only feature (no iOS counterpart) — BGGeoEngine.kt:1585, `?: 30_000L`.
                ConfigField(key = "stationaryLocationUpdateInterval", label = "Stationary interval", type = ConfigFieldType.NUMBER, default = 30000, unit = "ms"),
                // Android-only feature — BGGeoEngine.kt:1584, `?: 1000L`.
                ConfigField(key = "locationUpdateInterval", label = "Moving interval", type = ConfigFieldType.NUMBER, default = 1000, unit = "ms"),
                // BGGeoEngine.kt:717.
                ConfigField(key = "disableLocationFilter", label = "Disable Kalman filter", type = ConfigFieldType.BOOL, default = false),
                // BGGeoEngine.kt:720 — `?: 100.0`.
                ConfigField(key = "locationFilterMaxAccuracy", label = "Filter max accuracy", type = ConfigFieldType.NUMBER, default = 100.0, unit = "m"),
                // BGGeoEngine.kt:721 — `?: 60.0`.
                ConfigField(key = "locationFilterMaxSpeed", label = "Filter max speed", type = ConfigFieldType.NUMBER, default = 60.0, unit = "m/s"),
                // BGGeoEngine.kt:702-706 — unrecognized/missing falls to CONSERVATIVE.
                ConfigField(
                    key = "locationFilterPolicy", label = "Filter policy", type = ConfigFieldType.ENUM,
                    options = listOf(
                        ConfigFieldOption("CONS", "Conservative"),
                        ConfigFieldOption("ADJ", "Adjust"),
                        ConfigFieldOption("PASS", "PassThrough"),
                    ),
                    default = "Conservative",
                ),
                // BGGeoEngine.kt:708-714 — unrecognized/missing falls to DEFAULT (1.0/1.0 scales).
                ConfigField(
                    key = "kalmanProfile", label = "Kalman profile", type = ConfigFieldType.ENUM,
                    options = listOf(
                        ConfigFieldOption("DEF", "DEFAULT"),
                        ConfigFieldOption("AGGR", "AGGRESSIVE"),
                        ConfigFieldOption("CONS", "CONSERVATIVE"),
                    ),
                    default = "DEFAULT",
                ),
                // BGGeoEngine.kt:1417 — `?: 0.0`.
                ConfigField(key = "odometerAccuracyThreshold", label = "Odometer accuracy gate", type = ConfigFieldType.NUMBER, default = 0.0, unit = "m", hint = "0 = off"),
            ),
        ),
        ConfigSection(
            title = "Motion / Activity",
            fields = listOf(
                // BGGeoEngine.kt:765 — `?: 5.0`.
                ConfigField(key = "stopTimeout", label = "Stop timeout", type = ConfigFieldType.NUMBER, default = 5, unit = "min"),
                // BGGeoEngine.kt:908 — `?: 0L`.
                ConfigField(key = "motionTriggerDelay", label = "Motion trigger delay", type = ConfigFieldType.NUMBER, default = 0, unit = "ms"),
                // CONFIRMED — engine default 75 (BGGeoEngine.kt:870: `?: 75`), NOT
                // iOS's 50. Real, deliberate cross-platform divergence: iOS's own
                // engine comment (`BGGeoEngine.mm:1553-1556`, quoted in the parity
                // report above) explains iOS uses a coarse Low/Med/High=33/66/100
                // Core Motion confidence scale where 50 preserves "anything above
                // Low counts as moving", while Android's activity-recognition
                // confidence is a fine-grained 0-100 percentage where 75 is the
                // engine's own considered default. This console is Android-only,
                // so there is no ambiguity to resolve the way RN/Flutter had to
                // (they share one schema across both platforms and picked iOS's 50
                // as a tiebreaker, per the parity report) — Android's own value is
                // used here with no compromise needed.
                ConfigField(key = "minimumActivityRecognitionConfidence", label = "Min AR confidence", type = ConfigFieldType.NUMBER, default = 75, unit = "%"),
                // BGGeoEngine.kt:865-868 falls back to
                // ActivityClassifier.DEFAULT_TRIGGER_ACTIVITIES (activity/
                // ActivityClassifier.kt:42-45: in_vehicle, on_bicycle, on_foot,
                // running, walking — same 5 names, CSV order here matches the
                // iOS/RN schema string for consistency, order is immaterial (a Set)).
                ConfigField(
                    key = "triggerActivities", label = "Trigger activities", type = ConfigFieldType.STRING,
                    default = "in_vehicle,on_bicycle,walking,running,on_foot",
                    hint = "CSV of activity names that count as \"moving\"",
                ),
                // Android-only feature — BGGeoEngine.kt:877, `?: 10_000L`.
                ConfigField(key = "activityRecognitionInterval", label = "AR poll interval", type = ConfigFieldType.NUMBER, default = 10000, unit = "ms"),
                // BGGeoEngine.kt:896.
                ConfigField(key = "disableMotionActivityUpdates", label = "Disable motion updates", type = ConfigFieldType.BOOL, default = false),
            ),
        ),
        ConfigSection(
            title = "Power",
            fields = listOf(
                // BGGeoEngine.kt:1583 — `?: false`.
                ConfigField(key = "disableElasticity", label = "Disable elasticity", type = ConfigFieldType.BOOL, default = false),
                // BGGeoEngine.kt:1582 — `?: 1.0`.
                ConfigField(key = "elasticityMultiplier", label = "Elasticity multiplier", type = ConfigFieldType.NUMBER, default = 1.0),
            ),
        ),
        ConfigSection(
            title = "HTTP / Sync",
            fields = listOf(
                // BGGeoHttpStore.kt:190 — `?: true`.
                ConfigField(key = "autoSync", label = "Auto sync", type = ConfigFieldType.BOOL, default = true),
                // BGGeoHttpStore.kt:196 — `?: 0`.
                ConfigField(key = "autoSyncThreshold", label = "Auto-sync threshold", type = ConfigFieldType.NUMBER, default = 0),
                // BGGeoHttpStore.kt:191 — `?: false`.
                ConfigField(
                    key = "disableAutoSyncOnCellular", label = "Wi-Fi-only auto sync", type = ConfigFieldType.BOOL,
                    default = false, hint = "explicit Sync still uploads on cellular",
                ),
                // BGGeoHttpStore.kt:197 — `?: false`.
                ConfigField(key = "batchSync", label = "Batch sync", type = ConfigFieldType.BOOL, default = false),
                // CONFIRMED — engine default -1/unbatched (BGGeoHttpStore.kt:60
                // field init, :198 config fallback). NOT the stale 50 the other
                // consoles shipped with before their own fix; `DeviceLink` (Task 3)
                // sets 50 once linked, independently of this default.
                ConfigField(
                    key = "maxBatchSize", label = "Max batch size", type = ConfigFieldType.NUMBER, default = -1,
                    hint = "DeviceLink sets 50 once linked, independently of this default",
                ),
                // CONFIRMED — engine default 30000 (BGGeoHttpStore.kt:199: `?: 30_000L`).
                // NOT the stale 60000 the other consoles shipped with before their own fix.
                ConfigField(key = "httpTimeoutMs", label = "HTTP timeout", type = ConfigFieldType.NUMBER, default = 30000, unit = "ms"),
                // BGGeoHttpStore.kt:161 — `?: "location"`.
                ConfigField(
                    key = "httpRootProperty", label = "HTTP root property", type = ConfigFieldType.STRING, default = "location",
                    hint = "\".\" merges a single record into the root",
                ),
                // BGGeoHttpStore.kt:40 (field init), :152-153 (unrecognized falls back to POST).
                ConfigField(
                    key = "method", label = "HTTP method", type = ConfigFieldType.ENUM,
                    options = listOf(ConfigFieldOption("POST", "POST"), ConfigFieldOption("PUT", "PUT"), ConfigFieldOption("PATCH", "PATCH")),
                    default = "POST",
                ),
            ),
        ),
        ConfigSection(
            title = "Persistence",
            fields = listOf(
                // BGGeoHttpStore.kt:195 — `?: -1`.
                ConfigField(key = "maxRecordsToPersist", label = "Max records", type = ConfigFieldType.NUMBER, default = -1, hint = "-1 = unlimited"),
                // BGGeoHttpStore.kt:194 — only applied when > 0; 0 means "leave the engine's own retention alone".
                ConfigField(key = "maxDaysToPersist", label = "Max days", type = ConfigFieldType.NUMBER, default = 0, unit = "d"),
            ),
        ),
        ConfigSection(
            title = "Geofencing",
            fields = listOf(
                // BGGeoEngine.kt:272 — `?: 1000.0`.
                ConfigField(key = "geofenceProximityRadius", label = "Proximity radius", type = ConfigFieldType.NUMBER, default = 1000.0, unit = "m"),
                // BGGeoEngine.kt:273 — `?: -1`.
                ConfigField(key = "maxMonitoredGeofences", label = "Max monitored", type = ConfigFieldType.NUMBER, default = -1, hint = "-1 = platform budget"),
                // BGGeoEngine.kt:274 — `?: true`.
                ConfigField(key = "geofenceInitialTriggerEntry", label = "Initial ENTER trigger", type = ConfigFieldType.BOOL, default = true),
            ),
        ),
        ConfigSection(
            title = "Application",
            fields = listOf(
                // BGGeoEngine.kt:1077 — `?: 60.0`.
                ConfigField(key = "heartbeatInterval", label = "Heartbeat interval", type = ConfigFieldType.NUMBER, default = 60, unit = "s"),
                // `true`, because `baseConfig` (ExampleApp.kt) boots with it —
                // NOT the engine's own `false` fallback (BGGeoEngine.kt:363 —
                // `?: false`). See this file's header for which of the two a
                // `default` records now that a base config exists; RN/iOS
                // declare the same `true` for the same reason.
                ConfigField(key = "stopOnTerminate", label = "Stop on terminate", type = ConfigFieldType.BOOL, default = true),
                // BGGeoEngine.kt:356 — `?: false`, and `baseConfig` agrees.
                ConfigField(key = "startOnBoot", label = "Start on boot", type = ConfigFieldType.BOOL, default = false),
                // `true` from `baseConfig`, not the engine's `false`
                // (BGGeoEngine.kt:748: `!= true` gate) — same
                // baseConfig-vs-engine case as `stopOnTerminate` above.
                ConfigField(key = "debug", label = "Debug sounds", type = ConfigFieldType.BOOL, default = true),
            ),
        ),
        ConfigSection(
            title = "Diagnostics / Engine",
            fields = listOf(
                // 3/INFO from `baseConfig`, not the engine's own 0/OFF
                // fallback (BGGeoEngine.kt:266 — `?: 0`) — same
                // baseConfig-vs-engine case as `stopOnTerminate`/`debug`
                // above, and the same value RN/iOS boot with.
                ConfigField(
                    key = "logLevel", label = "Log level", type = ConfigFieldType.ENUM,
                    options = listOf(
                        ConfigFieldOption("OFF", 0), ConfigFieldOption("ERR", 1), ConfigFieldOption("WARN", 2),
                        ConfigFieldOption("INFO", 3), ConfigFieldOption("DBG", 4), ConfigFieldOption("VERB", 5),
                    ),
                    default = 3,
                    hint = "native log persistence (mirror to logcat is always on)",
                ),
                // BGGeoHttpStore.kt:193 — `?: 3` (then coerced to >= 1).
                ConfigField(key = "logMaxDays", label = "Log retention", type = ConfigFieldType.NUMBER, default = 3, unit = "d"),
                // Accepted by Config/setConfig but not yet read anywhere in the
                // Android engine (confirmed by grep) — see file header. Kept in the
                // schema since Config.kt does not tag it `@platform ios`.
                ConfigField(key = "diagnosticExtras", label = "Diagnostic extras", type = ConfigFieldType.BOOL, default = false),
            ),
        ),
        ConfigSection(
            title = "Notification",
            fields = listOf(
                // BGGeoEngine.kt:510-511 — `?: "Location"`.
                ConfigField(key = "notification.title", label = "Title", type = ConfigFieldType.STRING, default = "Location"),
                // BGGeoEngine.kt:513-514 — `?: "Location tracking active"`.
                ConfigField(key = "notification.text", label = "Text", type = ConfigFieldType.STRING, default = "Location tracking active"),
                // BGGeoEngine.kt:516-517 — `?: "bgeo_location_min"`.
                ConfigField(
                    key = "notification.channelId", label = "Channel ID", type = ConfigFieldType.STRING, default = "bgeo_location_min",
                    hint = "importance is frozen per channel — change the ID to change priority",
                ),
                // BGGeoEngine.kt:519-520 — `?: "Location"`.
                ConfigField(key = "notification.channelName", label = "Channel name", type = ConfigFieldType.STRING, default = "Location"),
                // BGGeoEngine.kt:544-551 — empty = app icon.
                ConfigField(
                    key = "notification.smallIcon", label = "Small icon", type = ConfigFieldType.STRING, default = "",
                    hint = "drawable/name or mipmap/name; empty = app icon",
                ),
                // BGGeoEngine.kt:555-558 — empty = none.
                ConfigField(key = "notification.color", label = "Accent color", type = ConfigFieldType.STRING, default = "", hint = "#RRGGBB; empty = none"),
                // BGGeoEngine.kt:523-524 — `?: -2`.
                ConfigField(
                    key = "notification.priority", label = "Priority", type = ConfigFieldType.ENUM,
                    options = listOf(
                        ConfigFieldOption("MIN", -2), ConfigFieldOption("LOW", -1), ConfigFieldOption("DEF", 0),
                        ConfigFieldOption("HIGH", 1), ConfigFieldOption("MAX", 2),
                    ),
                    default = -2,
                ),
            ),
        ),
    )

    val fields: List<ConfigField> = sections.flatMap { it.fields }

    private val byKey: Map<String, ConfigField> = fields.associateBy { it.key }

    fun field(key: String): ConfigField? = byKey[key]

    /** The schema's declared engine default for `key`, or null if `key` isn't in any section. */
    fun defaultFor(key: String): Any? = byKey[key]?.default

    /** All schema keys under a dot prefix, e.g. `"notification."` -> every `notification.*` key. */
    fun keysWithPrefix(prefix: String): List<String> = fields.map { it.key }.filter { it.startsWith(prefix) }
}

/**
 * Reads an override/default `Any` value into a specific Kotlin type, and
 * parses a settings-field text draft into a number. Shared by [ConfigStore]
 * (building `Config` patches, decoding persisted JSON) and `SettingsScreen`
 * (rendering/parsing field values).
 */
object ConfigCoerce {
    fun bool(value: Any?): Boolean? = value as? Boolean

    /**
     * `org.json` (20240303, the real jar `JsonRuntimeTest` guards against the
     * android.jar stub) decodes a JSON number into `Integer`/`Long`/`Double`/
     * `BigDecimal`/`BigInteger` depending on its literal shape — e.g. `25.0`
     * comes back as `BigDecimal`, not `Double`. Every numeric branch here
     * must handle all five, not just the Kotlin-native ones a value built
     * in-process would have.
     */
    fun int(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in INT_RANGE }?.toInt()
        is Double -> roundToIntOrNull(value)
        is java.math.BigDecimal -> roundToIntOrNull(value.toDouble())
        is java.math.BigInteger -> value.toLong().takeIf { it in INT_RANGE }?.toInt()
        else -> null
    }

    fun double(value: Any?): Double? = when (value) {
        is Double -> value
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is java.math.BigDecimal -> value.toDouble()
        is java.math.BigInteger -> value.toDouble()
        else -> null
    }

    fun string(value: Any?): String? = value as? String

    /**
     * Parses a settings-field text draft into the numeric type matching
     * [isIntKind]. Returns null — never a wrong number — for unparsable text
     * or a value outside the representable range, instead of hitting either
     * of the two JVM number hazards a naive `text.toDouble().toInt()` would:
     * `"1e400".toDouble()` silently returns `+Infinity` rather than throwing,
     * and `Double.toInt()` silently saturates (`NaN` -> 0, `Infinity` ->
     * `Int.MAX_VALUE`) rather than throwing — a digits keyboard makes an
     * arbitrarily large/malformed numeric string trivially reachable from a
     * normal settings edit, so this must reject those instead of coercing
     * them into a wrong config value.
     */
    fun numberFromText(text: String, isIntKind: Boolean): Any? {
        val parsed = text.toDoubleOrNull() ?: return null
        if (parsed.isNaN() || parsed.isInfinite()) return null
        return if (isIntKind) roundToIntOrNull(parsed) else parsed
    }

    private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    /** Rounds first so an integral-valued Double (42.0) still coerces; null (not a wrong number) once rounding falls outside Int's range. */
    private fun roundToIntOrNull(value: Double): Int? {
        if (value.isNaN() || value.isInfinite()) return null
        return Math.round(value).takeIf { it in INT_RANGE }?.toInt()
    }
}
