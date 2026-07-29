package com.bgeo.sdk

import org.json.JSONObject

/**
 * App-facing log writer. Every call rides the same persisted log store and
 * uploader as the engine's own diagnostic lines.
 *
 * **Divergence from `react-native/src/index.ts`:** RN writes `src: "js"`;
 * this writes **`src = "native"`** — this IS a native app, and `src` is the
 * field the bgeo.dev web console uses to separate engine-authored log lines
 * from app-authored ones, so it must name what actually wrote the line.
 *
 * Unlike iOS, [tag] IS meaningful here: it's the Android logcat category
 * (`adb logcat -s MyTag`), so it stays a parameter with the engine's
 * `"BGGeo"` default (`react-native/src/index.ts:293-309`).
 */
class Logger internal constructor(private val engine: Engine) {
    fun error(message: String, data: JSONObject? = null, tag: String = "BGGeo") = write(LogLevel.ERROR, message, data, tag)
    fun warn(message: String, data: JSONObject? = null, tag: String = "BGGeo") = write(LogLevel.WARNING, message, data, tag)
    fun info(message: String, data: JSONObject? = null, tag: String = "BGGeo") = write(LogLevel.INFO, message, data, tag)
    fun debug(message: String, data: JSONObject? = null, tag: String = "BGGeo") = write(LogLevel.DEBUG, message, data, tag)
    fun verbose(message: String, data: JSONObject? = null, tag: String = "BGGeo") = write(LogLevel.VERBOSE, message, data, tag)

    private fun write(level: LogLevel, message: String, data: JSONObject?, tag: String) {
        engine.log(level.value, "app", message, data?.toString(), tag, "native")
    }
}

val BackgroundGeolocation.logger: Logger get() = Logger(engine)

/** Newest-first persisted log entries, capped to `1...5000` (`react-native/src/index.ts:311-314`). */
suspend fun BackgroundGeolocation.getLog(limit: Int = 500): List<LogEntry> =
    engine.newestLogs(limit.coerceIn(1, 5000)).mapNotNull(LogEntry::from)

suspend fun BackgroundGeolocation.destroyLog(): Int = engine.deleteAllLogs()

/**
 * Reads [Engine.pendingLogCount] BEFORE [Engine.flushLogs] and returns that
 * count — draining first would always report zero
 * (`react-native/src/index.ts:327-330`).
 */
suspend fun BackgroundGeolocation.uploadLog(): Int {
    val pending = engine.pendingLogCount()
    engine.flushLogs()
    return pending
}
