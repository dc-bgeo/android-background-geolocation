package dev.bgeo.example

import org.json.JSONObject
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Hybrid history source for the Map screen's from/to range: server history
 * when the device is linked (same data the web console shows), otherwise the
 * local session buffer filtered by timestamp.
 *
 * A Kotlin port of `react-native/example/src/history.ts`;
 * `ios/Example/Sources/History.swift` is the same port for iOS. Deliberately
 * a plain object with no Android/Compose imports so it stays unit-testable
 * under this module's `isReturnDefaultValues` harness — see `HistoryTest`.
 *
 * **Not wired into `MapScreen.kt` by this task.** Both reference clients use
 * this data source ONLY for the Map screen's history range bar
 * (`history.ts`'s sole consumer is `MapScreen.tsx`; `LogsScreen.tsx`/
 * `.swift` never touch it), and `MapScreen.kt`'s own header already
 * documents that windowing/range UI was deliberately deferred out of Task 5.
 * This task's file list builds the data layer only (`History.kt` +
 * `HistoryTest`) — it does not touch `MapScreen.kt` or build a
 * `DateTimeField.kt` picker, since neither is on this task's file list and
 * neither has any other caller yet. See the task report for the reasoning.
 */
object History {

    /**
     * Pure: `history.ts`'s `filterPointsByRange`. Both bounds inclusive;
     * either/both may be `null` (no bound on that side). Diverges from the
     * RN original in one respect: an unparsable [from]/[to] bound is treated
     * as *absent* rather than RN's `Date.parse` -> `NaN`, which (since every
     * `>=`/`<=` comparison against `NaN` is `false` in JS) would silently
     * exclude every point instead of failing safe.
     */
    fun filterPointsByRange(points: List<Point>, from: String?, to: String?): List<Point> {
        val fromMs = from?.let(::parseIsoMillis)
        val toMs = to?.let(::parseIsoMillis)
        return points.filter { point ->
            val t = parseIsoMillis(point.timestamp) ?: return@filter false
            (fromMs == null || t >= fromMs) && (toMs == null || t <= toMs)
        }
    }

    /**
     * `history.ts`'s `serverLocationToPoint` — the console's `/v1` +
     * `/device` history camelCase shape -> [Point]. Unlike the RN original
     * (which never drops a row), returns `null` when a required field
     * (`recordedAt`/`lat`/`lng`) is missing or the wrong type, matching this
     * codebase's `Models.kt` decoding convention and iOS's
     * `HistoryLoader.point(fromServerJSON:)`. `geofence` is deliberately not
     * decoded: server history doesn't carry per-point geofence detail today
     * (same note as the iOS port).
     */
    fun pointFromServerJson(json: JSONObject): Point? {
        val timestamp = json.stringOrNull("recordedAt") ?: return null
        val latitude = json.doubleOrNull("lat") ?: return null
        val longitude = json.doubleOrNull("lng") ?: return null
        return Point(
            uuid = json.stringOrNull("uuid"),
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = json.doubleOrNull("accuracy"),
            speed = json.doubleOrNull("speed"),
            heading = json.doubleOrNull("heading"),
            odometer = json.doubleOrNull("odometer"),
            activity = json.stringOrNull("activityType") ?: json.stringOrNull("activity"),
            isMoving = json.boolOrNull("isMoving"),
            event = json.stringOrNull("event"),
        )
    }

    /**
     * `history.ts`'s `loadHistory`: server history via `GET
     * {base}/device/locations?limit=2000&from=&to=` when [linked] (server
     * returns newest-first; reversed here to oldest-first for a polyline),
     * falling back to the local buffer filtered by range when not linked OR
     * on any request/decode failure — matching RN's `deviceFetch`, which
     * swallows failures and returns `null` rather than throwing, unlike
     * `DeviceLink.authorizedFetch` here, which throws `DeviceLinkError`.
     */
    suspend fun load(
        deviceLink: DeviceLink,
        linked: Boolean,
        localPoints: List<Point>,
        from: String? = null,
        to: String? = null,
    ): List<Point> {
        if (linked) {
            val query = buildString {
                append("limit=2000")
                from?.let { append("&from=").append(encode(it)) }
                to?.let { append("&to=").append(encode(it)) }
            }
            val points = try {
                val response = deviceLink.authorizedFetch("/device/locations?$query")
                if (response.status !in 200..299) {
                    null
                } else {
                    val body = JSONObject(response.body)
                    if (body.isNull("locations")) {
                        null
                    } else {
                        body.optJSONArray("locations")?.let { array ->
                            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::pointFromServerJson) }
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (points != null) return points.reversed()
        }
        return filterPointsByRange(localPoints, from, to)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

// ---- local JSON helpers (per-file convention: `DeviceLink.kt` keeps its own
// private `stringOrNull` rather than a shared cross-file utility; this file
// needs the double/bool variants too). Every `is*` check below rejects a
// present-but-wrong-typed value instead of coercing it, same reasoning as
// `sdk/.../JsonDecoding.kt` (not directly reusable: those helpers are
// `internal` to the `:sdk` module). ----

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) (opt(key) as? String) else null

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toDouble() else null

private fun JSONObject.boolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) (opt(key) as? Boolean) else null

/**
 * Parses either fractional- or whole-second ISO 8601 (the two shapes a
 * server timestamp or a `Point.timestamp` may arrive in), pinned to
 * `Locale.US` + UTC — same two-pattern convention as
 * `CoordinatesSheet.kt`'s `PointFormat.parseIso` and `MapScreen.kt`'s
 * `isoNow()`. Returns `null` (never throws) for anything else, so a
 * malformed timestamp drops that one point from a range filter rather than
 * crashing the whole screen.
 */
private fun parseIsoMillis(value: String): Long? {
    for (pattern in ISO_PATTERNS) {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val parsed: Date? = try {
            format.parse(value)
        } catch (e: ParseException) {
            null
        }
        if (parsed != null) return parsed.time
    }
    return null
}

private val ISO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
)
