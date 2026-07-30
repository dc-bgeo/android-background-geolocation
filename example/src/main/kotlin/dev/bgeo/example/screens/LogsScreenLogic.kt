package dev.bgeo.example.screens

import com.bgeo.sdk.LogEntry
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogLine

/**
 * Pure logic for the Logs screen — no Android/Compose imports, so it stays
 * unit-testable under this module's `isReturnDefaultValues` harness, same
 * reasoning and pattern as Task 5's `MapRebuild.kt` (which a reviewer
 * credited with closing that screen's defect class precisely because its
 * decision logic was pulled out of the Compose file): `LogsScreen.kt` calls
 * every function here and adds nothing decision-shaped of its own.
 *
 * A Kotlin port of the pure parts of `react-native/example/src/screens/
 * LogsScreen.tsx`; `ios/Example/Sources/Screens/LogsScreen.swift`'s
 * top-of-file "Pure logic (tested)" section is the same port for iOS and is
 * followed closely here, including its filter key.
 *
 * **`event == "app"` is this platform's `src` filter, same as iOS.** RN
 * polls `getLog()` and keeps only `src === 'native'`, because its
 * `logUploader.ts` ALSO writes every JS-authored line into that same native
 * queue tagged `src:"js"` — without the filter, a line already streaming
 * live via `appStore` would double-count once the poll catches up to it.
 * Android's `Logger.write` (`sdk/.../Logger.kt`) always writes `src =
 * "native"` (see that file's own doc comment) AND hard-codes `event = "app"`
 * for every write it makes — the real event name/payload travel inside
 * `data` instead (see `LogUploader.kt`'s header). The engine's OWN
 * diagnostic lines are dot-namespaced and never `"app"`. So `event == "app"`
 * distinguishes exactly the same two sources RN's `src` does, just keyed on
 * a different field — [nativeLogEntries] drops them from a `getLog()` poll
 * before the merge, mirroring RN's filter and iOS's `nativeLogEntries(from:)`.
 */

/** Drops `LogUploader`-authored entries (`event == "app"`) from a raw `getLog()` poll. Genuine engine diagnostics pass through unchanged. */
fun nativeLogEntries(entries: List<LogEntry>): List<LogEntry> = entries.filter { it.event != "app" }

/**
 * `LogsScreen.tsx`'s `LEVEL_NAMES` map: the native numeric Transistor scale
 * (`LogEntry.level`, 1=ERROR..5=VERBOSE, matching `com.bgeo.sdk.LogLevel`'s
 * own `value`s) -> this app's [LogLevel], falling back to [LogLevel.INFO]
 * for anything unrecognised (`0`/OFF, or an out-of-range value) — same
 * fallback RN's `?? 'info'` uses.
 */
fun logLineFromEntry(entry: LogEntry): LogLine {
    val level = when (entry.level) {
        1 -> LogLevel.ERROR
        2 -> LogLevel.WARN
        3 -> LogLevel.INFO
        4 -> LogLevel.DEBUG
        5 -> LogLevel.VERBOSE
        else -> LogLevel.INFO
    }
    return LogLine(ts = entry.ts, level = level, event = entry.event, message = entry.message, data = entry.data)
}

/**
 * `LogsScreen.tsx`'s `merged` + `filtered` memos: sort by `ts` (ISO 8601
 * strings compare correctly under plain lexicographic ordering — Kotlin's
 * `String` comparison is ordinal/UTF-16-code-unit based, not locale-aware,
 * so this needs no `Locale` pinning the way a date/number *format* would),
 * then apply the level filter. `level == null` means "all levels" (the
 * chip row's extra option with no counterpart in [LogLevel] itself).
 */
fun mergeAndFilterLogs(appLogs: List<LogLine>, nativeLines: List<LogLine>, level: LogLevel?): List<LogLine> {
    val merged = (appLogs + nativeLines).sortedBy { it.ts }
    return if (level == null) merged else merged.filter { it.level == level }
}

/**
 * `line.ts.slice(11, 23)` — the `HH:mm:ss.SSS` slice of an ISO timestamp,
 * safely bounded for a shorter-than-expected string instead of throwing
 * `StringIndexOutOfBoundsException`.
 */
fun logTimeSlice(ts: String): String {
    if (ts.length <= 11) return ts
    return ts.substring(11, minOf(ts.length, 23))
}
