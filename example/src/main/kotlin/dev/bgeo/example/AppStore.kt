package dev.bgeo.example

import com.bgeo.sdk.Geofence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tiny shared store for the example app: structured log lines (same shape as
 * `/device/logs` events), breadcrumb points, the SDK geofence set, engine
 * status and the device-link state.
 *
 * A Kotlin port of `react-native/example/src/appStore.ts` (the cross-client
 * contract); `ios/Example/Sources/AppStore.swift` is the same port for iOS
 * and agrees with every decision made here. Deliberately a plain class with
 * no Android or Compose imports so it stays JVM-unit-testable.
 */

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Exactly the event shape uploaded to /device/logs — what you see in the app is what the web console shows. */
data class LogLine(
    val ts: String, // ISO
    val level: LogLevel,
    val event: String,
    val message: String? = null,
    val data: Any? = null,
)

/** Which region fired on an `event:"geofence"` point, and how. */
data class PointGeofence(
    val identifier: String,
    val action: String? = null,
)

data class Point(
    val uuid: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String, // ISO
    val accuracy: Double? = null,
    val speed: Double? = null,
    val heading: Double? = null,
    val odometer: Double? = null, // metres
    val activity: String? = null,
    val isMoving: Boolean? = null,
    val event: String? = null,
    val geofence: PointGeofence? = null,
)

data class LinkState(
    val serverUrl: String = "https://app.bgeo.dev",
    val linked: Boolean = false,
    val deviceId: String? = null,
)

data class EngineStatus(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val isMoving: Boolean = false,
    val batteryLevel: Double? = null,
)

class AppStore {
    companion object {
        /** `appStore.ts`: `logs: [...state.logs.slice(-999), line]` — 999 kept + the new one = 1000 max. */
        const val MAX_LOGS = 1000

        /** `appStore.ts`: `points: [...state.points.slice(-1999), point]` — 1999 kept + the new one = 2000 max. */
        const val MAX_POINTS = 2000
    }

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _points = MutableStateFlow<List<Point>>(emptyList())
    val points: StateFlow<List<Point>> = _points.asStateFlow()

    private val _geofences = MutableStateFlow<List<Geofence>>(emptyList())
    val geofences: StateFlow<List<Geofence>> = _geofences.asStateFlow()

    private val _link = MutableStateFlow(LinkState())
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun appendLog(line: LogLine) {
        _logs.update { (it + line).takeLast(MAX_LOGS) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun appendPoint(point: Point) {
        _points.update { (it + point).takeLast(MAX_POINTS) }
    }

    /** `appStore.ts`'s `clearTrack` — used by the settings screen to clear the breadcrumb buffer. */
    fun clearTrack() {
        _points.value = emptyList()
    }

    fun setGeofences(geofences: List<Geofence>) {
        _geofences.value = geofences
    }

    /**
     * Partial update: only the parameters passed override the current link state (mirrors
     * `appStore.ts`'s `setLink(link: Partial<LinkState>)`). `deviceId` needs an explicit clear
     * flag, not a plain nullable parameter, because Kotlin can't distinguish "omitted" from
     * "passed null" through a single `String?` parameter — same reasoning as `AppStore.swift`.
     */
    fun setLink(
        serverUrl: String? = null,
        linked: Boolean? = null,
        deviceId: String? = null,
        clearDeviceId: Boolean = false,
    ) {
        _link.update { current ->
            current.copy(
                serverUrl = serverUrl ?: current.serverUrl,
                linked = linked ?: current.linked,
                deviceId = if (clearDeviceId) null else deviceId ?: current.deviceId,
            )
        }
    }

    /** Partial update, same shape as `setStatus(status: Partial<EngineStatus>)`. */
    fun setStatus(
        ready: Boolean? = null,
        enabled: Boolean? = null,
        isMoving: Boolean? = null,
        batteryLevel: Double? = null,
    ) {
        _status.update { current ->
            current.copy(
                ready = ready ?: current.ready,
                enabled = enabled ?: current.enabled,
                isMoving = isMoving ?: current.isMoving,
                batteryLevel = batteryLevel ?: current.batteryLevel,
            )
        }
    }
}
