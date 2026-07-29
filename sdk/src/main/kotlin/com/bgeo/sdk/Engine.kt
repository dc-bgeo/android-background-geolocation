package com.bgeo.sdk

import android.content.Context
import com.bgeo.BGGeoCallback
import com.bgeo.BGGeoDb
import com.bgeo.BGGeoEngine
import com.bgeo.BGGeoHttpStore
import com.bgeo.BGGeoLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The testability seam over the closed-source `dev.bgeo:bgeo-android` engine.
 * Everything in this package talks to the engine only through this interface,
 * so the facade can be unit-tested on the JVM against `FakeEngine` without a
 * device. One member per engine call used by
 * `react-native/android/src/main/java/com/bgeo/rn/BackgroundGeolocationModule.kt`.
 */
internal interface Engine {
    var eventEmitter: ((String, JSONObject) -> Unit)?

    fun init(context: Context)
    fun resumeTrackingIfEnabled()
    fun licenseErrorCode(): String?
    fun applyConfig(map: JSONObject?)
    fun stateMap(): JSONObject
    fun startTracking()
    fun stopTracking()
    fun changePace(isMoving: Boolean): Boolean
    fun currentOdometer(): Double
    fun setOdometer(value: Double, callback: BGGeoCallback)

    fun getCurrentPosition(options: JSONObject?, callback: BGGeoCallback)
    fun startWatch(options: JSONObject?)
    fun stopWatch()

    fun hasFineOrCoarse(): Boolean
    fun hasBackground(): Boolean
    fun hasActivityRecognition(): Boolean
    fun wantsAlways(): Boolean
    fun numericStatus(): Int
    fun providerState(): JSONObject
    fun emitProviderChange()
    fun isPowerSaveMode(): Boolean

    fun sync(callback: BGGeoCallback)
    fun getLocations(callback: BGGeoCallback)
    fun destroyLocations(callback: BGGeoCallback)
    fun pendingCount(): Int
    fun destroyLocation(uuid: String): Boolean
    fun insertLocation(location: JSONObject, callback: BGGeoCallback)
    fun authStateMap(): JSONObject

    fun newestLogs(limit: Int): List<JSONObject>
    fun deleteAllLogs(): Int
    fun pendingLogCount(): Int
    fun flushLogs()
    fun log(level: Int, event: String, message: String?, data: String?, tag: String, src: String)
    fun setLoggerForeground(foreground: Boolean)

    fun addGeofences(geofences: JSONArray?, callback: BGGeoCallback)
    fun removeGeofence(identifier: String, callback: BGGeoCallback)
    fun removeGeofences(callback: BGGeoCallback)
    fun getGeofences(callback: BGGeoCallback)
    fun geofenceExists(identifier: String, callback: BGGeoCallback)
}

/**
 * Thin forwarder to the real engine. No logic lives here — every member is a
 * 1:1 call-through, matching `BackgroundGeolocationModule.kt:400-428`. Most
 * members forward to `com.bgeo.BGGeoEngine`; the log query/upload members
 * forward to `com.bgeo.BGGeoDb` (`newestLogs`/`deleteAllLogs`) and
 * `com.bgeo.BGGeoHttpStore` (`pendingLogCount`/`flushLogs`); `log` and
 * `setLoggerForeground` forward to `com.bgeo.BGGeoLogger`.
 */
internal object LiveEngine : Engine {

    override var eventEmitter: ((String, JSONObject) -> Unit)?
        get() = BGGeoEngine.eventEmitter
        set(value) { BGGeoEngine.eventEmitter = value }

    override fun init(context: Context) = BGGeoEngine.init(context)
    override fun resumeTrackingIfEnabled() = BGGeoEngine.resumeTrackingIfEnabled()
    override fun licenseErrorCode(): String? = BGGeoEngine.licenseErrorCode()
    override fun applyConfig(map: JSONObject?) = BGGeoEngine.applyConfig(map)
    override fun stateMap(): JSONObject = BGGeoEngine.stateMap()
    override fun startTracking() = BGGeoEngine.startTracking()
    override fun stopTracking() = BGGeoEngine.stopTracking()
    override fun changePace(isMoving: Boolean): Boolean = BGGeoEngine.changePace(isMoving)
    override fun currentOdometer(): Double = BGGeoEngine.currentOdometer()
    override fun setOdometer(value: Double, callback: BGGeoCallback) = BGGeoEngine.setOdometer(value, callback)

    override fun getCurrentPosition(options: JSONObject?, callback: BGGeoCallback) =
        BGGeoEngine.getCurrentPosition(options, callback)
    override fun startWatch(options: JSONObject?) = BGGeoEngine.startWatch(options)
    override fun stopWatch() = BGGeoEngine.stopWatch()

    override fun hasFineOrCoarse(): Boolean = BGGeoEngine.hasFineOrCoarse()
    override fun hasBackground(): Boolean = BGGeoEngine.hasBackground()
    override fun hasActivityRecognition(): Boolean = BGGeoEngine.hasActivityRecognition()
    override fun wantsAlways(): Boolean = BGGeoEngine.wantsAlways()
    override fun numericStatus(): Int = BGGeoEngine.numericStatus()
    override fun providerState(): JSONObject = BGGeoEngine.providerState()
    override fun emitProviderChange() = BGGeoEngine.emitProviderChange()
    override fun isPowerSaveMode(): Boolean = BGGeoEngine.isPowerSaveMode()

    override fun sync(callback: BGGeoCallback) = BGGeoEngine.sync(callback)
    override fun getLocations(callback: BGGeoCallback) = BGGeoEngine.getLocations(callback)
    override fun destroyLocations(callback: BGGeoCallback) = BGGeoEngine.destroyLocations(callback)
    override fun pendingCount(): Int = BGGeoEngine.pendingCount()
    override fun destroyLocation(uuid: String): Boolean = BGGeoEngine.destroyLocation(uuid)
    override fun insertLocation(location: JSONObject, callback: BGGeoCallback) =
        BGGeoEngine.insertLocation(location, callback)
    override fun authStateMap(): JSONObject = BGGeoEngine.authStateMap()

    // BGGeoDb.newestLogs returns its own LogRow type (id/tsMs/level/src/event/
    // message/data), not JSONObject — logRowToJson (below) is the same shape
    // the RN bridge builds in BackgroundGeolocationModule.kt's getLog(),
    // needed because the Engine contract returns JSONObject uniformly for
    // the facade.
    override fun newestLogs(limit: Int): List<JSONObject> = BGGeoDb.newestLogs(limit).map(::logRowToJson)
    override fun deleteAllLogs(): Int = BGGeoDb.deleteAllLogs()
    override fun pendingLogCount(): Int = BGGeoHttpStore.pendingLogCount()
    override fun flushLogs() = BGGeoHttpStore.flushLogs()
    override fun log(level: Int, event: String, message: String?, data: String?, tag: String, src: String) =
        BGGeoLogger.log(level, event, message, data, tag, src)
    override fun setLoggerForeground(foreground: Boolean) { BGGeoLogger.foreground = foreground }

    override fun addGeofences(geofences: JSONArray?, callback: BGGeoCallback) =
        BGGeoEngine.addGeofences(geofences, callback)
    override fun removeGeofence(identifier: String, callback: BGGeoCallback) =
        BGGeoEngine.removeGeofence(identifier, callback)
    override fun removeGeofences(callback: BGGeoCallback) = BGGeoEngine.removeGeofences(callback)
    override fun getGeofences(callback: BGGeoCallback) = BGGeoEngine.getGeofences(callback)
    override fun geofenceExists(identifier: String, callback: BGGeoCallback) =
        BGGeoEngine.geofenceExists(identifier, callback)
}

// SimpleDateFormat is not thread-safe; newestLogs can be called from several
// threads. Same pattern/format as BGGeoEngine.isoFormatter.
private val logTimestampFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

/**
 * Maps one [BGGeoDb.LogRow] to the JSONObject shape `LogEntry.from` (Models.kt)
 * decodes: `ts`/`level`/`src`/`event`/`message`?/`data`?. `data` is re-parsed
 * into a [JSONObject] when the stored string is valid JSON (matching the RN
 * bridge's `getLog()`, `BackgroundGeolocationModule.kt:413`), falling back to
 * the raw string otherwise — both shapes are real and `LogEntry.data` is
 * typed `Any?` to carry either. Exposed at file scope (not private) so
 * `ModelDecodingTest` can prove the round trip through `LogEntry.from`
 * without needing a real SQLite-backed `BGGeoDb`.
 */
internal fun logRowToJson(row: BGGeoDb.LogRow): JSONObject = JSONObject().apply {
    put("ts", logTimestampFormatter.get()!!.format(Date(row.tsMs)))
    put("level", row.level)
    put("src", row.src)
    put("event", row.event)
    row.message?.let { put("message", it) }
    row.data?.let { raw -> put("data", runCatching { JSONObject(raw) }.getOrNull() ?: raw) }
}
