package dev.bgeo.example

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * History source for the Map screen's from/to range: the local session
 * buffer filtered by timestamp.
 *
 * A Kotlin port of `react-native/example/src/history.ts`;
 * `ios/Example/Sources/History.swift` is the same port for iOS. Deliberately
 * a plain object with no Android/Compose imports so it stays unit-testable
 * under this module's `isReturnDefaultValues` harness — see `HistoryTest`.
 *
 * Consumed by `MapScreen.kt`'s range bar, and nothing else — same as both
 * reference clients (`history.ts`'s sole consumer is `MapScreen.tsx`;
 * `LogsScreen.tsx`/`.swift` never touch it).
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
     * A picked range bound (epoch millis, from the Map screen's date/time
     * dialogs) -> the bound format [filterPointsByRange] takes and
     * [parseIsoMillis] reads back. UTC, whole seconds: the same shape
     * `Date.toISOString()` produces for RN and `Date.ISO8601Format` for iOS.
     */
    fun isoUtc(millis: Long): String =
        SimpleDateFormat(ISO_PATTERNS[1], Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}

/**
 * Parses either fractional- or whole-second ISO 8601 (the two shapes a
 * `Point.timestamp` or a range bound may arrive in), pinned to
 * `Locale.US` + UTC — same two-pattern convention as
 * `CoordinatesSheet.kt`'s `PointFormat.parseIso` and `MapScreen.kt`'s
 * `isoNow()`. Returns `null` (never throws) for anything else, so a
 * malformed timestamp drops that one point from a range filter rather than
 * crashing the whole screen.
 *
 * `internal`, not `private`: every existing test only calls this indirectly
 * through [History.filterPointsByRange], which applies
 * the SAME offset to every timestamp they parse in a single call, so a
 * dropped `TimeZone.getTimeZone("UTC")` pin here would shift every point and
 * every `from`/`to` bound by the same amount and cancel out of every
 * relative/inclusion assertion — see `HistoryTest`'s `parseIsoMillis`-level
 * test, which needs to call this directly against an independently-computed
 * `java.time.Instant` reference to actually catch that regression.
 */
internal fun parseIsoMillis(value: String): Long? {
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
