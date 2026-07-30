package dev.bgeo.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.Config
import com.bgeo.sdk.ConnectivityChangeEvent
import com.bgeo.sdk.GeofenceEvent
import com.bgeo.sdk.GeofencesChangeEvent
import com.bgeo.sdk.HeartbeatEvent
import com.bgeo.sdk.HttpEvent
import com.bgeo.sdk.Location
import com.bgeo.sdk.MotionChangeEvent
import com.bgeo.sdk.PermissionRequester
import com.bgeo.sdk.ProviderChangeEvent
import com.bgeo.sdk.State
import com.bgeo.sdk.onGeofence
import com.bgeo.sdk.onGeofencesChange
import dev.bgeo.example.screens.GeofenceFormScreen
import dev.bgeo.example.screens.GeofenceRequest
import dev.bgeo.example.screens.LogsScreen
import dev.bgeo.example.screens.MapScreen
import dev.bgeo.example.screens.SettingsScreen
import dev.bgeo.example.ui.ExampleScaffold
import dev.bgeo.example.ui.ExampleTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * App-level wiring: the base config, the nine event subscriptions, and the
 * root composable that hangs the three screens off [ExampleScaffold].
 *
 * A Kotlin port of `react-native/example/App.tsx`;
 * `ios/Example/Sources/BGeoExampleApp.swift` is the same port for iOS.
 * [BackgroundGeolocation.attach] is deliberately NOT here — it belongs to
 * `ExampleApplication.onCreate`, see that file.
 */

/**
 * The base config handed to `ready()`, before the user's Settings overrides
 * (`ConfigStore.merged(into:)`) are layered on top. The same six keys, with
 * the same values, as `react-native/example/src/configSchema.ts`'s
 * `BASE_CONFIG` and `BGeoExampleApp.swift`'s `baseConfig`.
 *
 * COUPLED WITH `ConfigSchema`: for every key set here,
 * `ConfigSchema.defaultFor(key)` must equal the value below — that column is
 * what the Settings screen displays as "the value you get when you have not
 * overridden this", and Reset pushes it back to the live engine. Three of
 * these (`stopOnTerminate`, `debug`, `logLevel`) deliberately differ from the
 * Android engine's own compiled-in fallbacks, which is exactly why the
 * coupling has to be pinned rather than assumed: `ExampleAppTest` fails if
 * the two ever drift. Without that, Settings would state
 * `Stop on terminate: false` / `Debug sounds: false` / `Log level: OFF`
 * while the engine ran `true`/`true`/`INFO`, and Reset would silently kill
 * debug sounds and all native log persistence — two of the things this
 * console exists to demonstrate.
 */
val baseConfig = Config(
    distanceFilter = 10.0,
    stopTimeout = 5,
    stopOnTerminate = true,
    startOnBoot = false,
    debug = true,
    // Native logger at INFO for the example app; upload starts once a device
    // link supplies `logUrl` (`DeviceLink.applySdkConfig`).
    logLevel = 3,
)

/**
 * The nine SDK event streams [Bootstrap] subscribes to, behind an interface.
 *
 * Same test-seam reasoning as `DeviceLink.applyConfig` and
 * `Geofences.addGeofenceCall`: `BackgroundGeolocation` is a Kotlin `object`
 * with static members, so it cannot be swapped for a fake. Routing the
 * subscriptions through this interface lets `ExampleAppTest` capture the
 * handlers that are ACTUALLY registered and fire real event payloads at them
 * — rather than re-implementing the handler bodies in the test, which is how
 * a redaction test ends up proving nothing (trap 3 in the task brief).
 */
interface EventSubscriptions {
    fun onLocation(handler: (Location) -> Unit)
    fun onMotionChange(handler: (MotionChangeEvent) -> Unit)
    fun onHeartbeat(handler: (HeartbeatEvent) -> Unit)
    fun onProviderChange(handler: (ProviderChangeEvent) -> Unit)
    fun onAuthorization(handler: (JSONObject) -> Unit)
    fun onGeofence(handler: (GeofenceEvent) -> Unit)
    fun onGeofencesChange(handler: (GeofencesChangeEvent) -> Unit)
    fun onHttp(handler: (HttpEvent) -> Unit)
    fun onConnectivityChange(handler: (ConnectivityChangeEvent) -> Unit)
}

/** The real thing: every call goes to the facade. Subscriptions are never removed — this app lives as long as the process. */
object SdkEventSubscriptions : EventSubscriptions {
    override fun onLocation(handler: (Location) -> Unit) { BackgroundGeolocation.onLocation(handler) }
    override fun onMotionChange(handler: (MotionChangeEvent) -> Unit) { BackgroundGeolocation.onMotionChange(handler) }
    override fun onHeartbeat(handler: (HeartbeatEvent) -> Unit) { BackgroundGeolocation.onHeartbeat(handler) }
    override fun onProviderChange(handler: (ProviderChangeEvent) -> Unit) { BackgroundGeolocation.onProviderChange(handler) }
    override fun onAuthorization(handler: (JSONObject) -> Unit) { BackgroundGeolocation.onAuthorization(handler) }
    override fun onGeofence(handler: (GeofenceEvent) -> Unit) { BackgroundGeolocation.onGeofence(handler) }
    override fun onGeofencesChange(handler: (GeofencesChangeEvent) -> Unit) { BackgroundGeolocation.onGeofencesChange(handler) }
    override fun onHttp(handler: (HttpEvent) -> Unit) { BackgroundGeolocation.onHttp(handler) }
    override fun onConnectivityChange(handler: (ConnectivityChangeEvent) -> Unit) { BackgroundGeolocation.onConnectivityChange(handler) }
}

/**
 * Brings the SDK up exactly once per process: subscribe, restore the device
 * link, then `ready()`.
 *
 * Ordering mirrors `App.tsx`'s effect and `BGeoExampleApp.swift`'s
 * `bootstrap()` line for line, and it matters: `EventHub` buffers up to 64
 * events per event name until a subscriber attaches, then LATCHES for that
 * name — one buffered replay, delivered to whichever subscriber attaches
 * first, never re-armed. Subscribing after `restore()`/`ready()`'s
 * `setConfig` risks losing launch-time events; subscribing before costs
 * nothing.
 */
class Bootstrap(
    private val store: AppStore,
    private val configStore: ConfigStore,
    private val deviceLink: DeviceLink,
    private val geofences: Geofences,
    private val logUploader: LogUploader,
    private val scope: CoroutineScope,
    private val events: EventSubscriptions = SdkEventSubscriptions,
    /** Test seam, same reasoning as [EventSubscriptions]. */
    private val readyCall: suspend (Config) -> State = { BackgroundGeolocation.ready(it) },
) {
    /**
     * Guards against a second run. Checked and set BEFORE the first
     * suspension point, so two callers on the main dispatcher cannot both get
     * past it: a second pass would double every subscription (each event
     * logged and each point appended twice, since subscriptions are never
     * removed) and call `ready()` again.
     *
     * This is reachable today: `ExampleApplication.onCreate` runs once per
     * process, but the process is also started headlessly for boot, geofence
     * and service events, and a `MainActivity` launched into that same
     * process afterwards must not bootstrap a second time.
     */
    private var hasBootstrapped = false

    suspend fun run() {
        if (hasBootstrapped) return
        hasBootstrapped = true

        subscribeToEvents()
        deviceLink.restore()

        try {
            val state = readyCall(configStore.merged(into = baseConfig))
            store.setStatus(ready = true, enabled = state.enabled)
            logUploader.logEvent("ready", LogLevel.INFO, "enabled=${state.enabled} odometer=${state["odometer"]}")
            geofences.refresh()
        } catch (e: Exception) {
            logUploader.logEvent("ready", LogLevel.ERROR, e.message ?: e.toString())
        }
    }

    private fun subscribeToEvents() {
        events.onLocation(::handleLocation)
        events.onMotionChange(::handleMotionChange)
        events.onHeartbeat(::handleHeartbeat)
        events.onProviderChange(::handleProviderChange)
        events.onAuthorization(::handleAuthorization)
        events.onGeofence(::handleGeofence)
        events.onGeofencesChange(::handleGeofencesChange)
        events.onHttp(::handleHttp)
        events.onConnectivityChange(::handleConnectivityChange)
    }

    internal fun handleLocation(location: Location) {
        store.appendPoint(location.toPoint())
        store.setStatus(isMoving = location.isMoving, batteryLevel = location.battery.level)
        val data = JSONObject()
            .put("lat", location.coords.latitude)
            .put("lng", location.coords.longitude)
            .put("accuracy", location.coords.accuracy)
            .put("isMoving", location.isMoving)
        location.extras?.let { data.put("extras", it) }
        logUploader.logEvent(
            "onLocation",
            LogLevel.DEBUG,
            message = String.format(
                java.util.Locale.US,
                "%.6f, %.6f ±%.0fm",
                location.coords.latitude,
                location.coords.longitude,
                location.coords.accuracy,
            ),
            data = data,
        )
    }

    internal fun handleMotionChange(event: MotionChangeEvent) {
        store.setStatus(isMoving = event.isMoving)
        logUploader.logEvent("onMotionChange", LogLevel.INFO, "isMoving=${event.isMoving}")
    }

    internal fun handleHeartbeat(event: HeartbeatEvent) {
        logUploader.logEvent("onHeartbeat", LogLevel.DEBUG)
    }

    internal fun handleProviderChange(event: ProviderChangeEvent) {
        val data = JSONObject()
            .put("status", event.status.value)
            .put("enabled", event.enabled)
            .put("gps", event.gps)
            .put("network", event.network)
        event.accuracyAuthorization?.let { data.put("accuracyAuthorization", it.value) }
        logUploader.logEvent("onProviderChange", LogLevel.WARN, "status=${event.status}", data)
    }

    /**
     * The raw event is `{success, accessToken, refreshToken}` — live JWTs.
     * Two things have to happen, and neither substitutes for the other
     * (task brief, step 0c): the real pair is persisted so the app-side
     * `authorizedFetch` keeps working after a native refresh, and the LOGGED
     * payload is reduced to presence booleans before it can reach the Logs
     * screen / `bgeo.db` / `/device/logs`.
     *
     * `LogUploader` would strip both tokens by key anyway; logging presence
     * booleans here is the same shape RN and iOS log, and means this call
     * site does not depend on that second line of defence.
     */
    internal fun handleAuthorization(event: JSONObject) {
        val success = event.optBoolean("success", false)
        val data = JSONObject()
            .put("success", success)
            .put("hasAccessToken", event.optString("accessToken").isNotEmpty())
            .put("hasRefreshToken", event.optString("refreshToken").isNotEmpty())
        logUploader.logEvent(
            "onAuthorization",
            if (success) LogLevel.INFO else LogLevel.ERROR,
            if (success) "refreshed" else "failed",
            data,
        )
        deviceLink.persistRotatedTokens(event)
    }

    internal fun handleGeofence(event: GeofenceEvent) {
        val data = JSONObject().put("identifier", event.identifier).put("action", event.action.wire)
        event.extras?.let { data.put("extras", it) }
        logUploader.logEvent("onGeofence", LogLevel.INFO, "${event.action.wire} ${event.identifier}", data)
        // Geofence transitions don't ride `onLocation` — append the point
        // here so the map and the coordinates table show them (same as
        // `App.tsx`, `BGeoExampleApp.swift` and the web console).
        store.appendPoint(
            event.location.toPoint(
                event = "geofence",
                geofence = PointGeofence(identifier = event.identifier, action = event.action.wire),
            ),
        )
    }

    internal fun handleGeofencesChange(event: GeofencesChangeEvent) {
        logUploader.logEvent("onGeofencesChange", LogLevel.DEBUG, "on=${event.on.size} off=${event.off.size}")
        scope.launch { geofences.refresh() }
    }

    /**
     * `responseText` is the response body VERBATIM, and it is logged that way
     * on every console (RN and iOS both pass the whole event through).
     *
     * Worth being precise about what that means for redaction:
     * `LogUploader`'s scrub is key-based — it can only recognise a credential
     * by the name of the key holding it. `responseText` is one opaque string
     * with no internal keys to match, so if the server ever echoed a token
     * back inside an error body, it would reach the Logs screen and
     * `/device/logs` unredacted. That is a limit of the key-based approach,
     * not a gap in its implementation, and it has never been claimed as
     * covered. The server side is what keeps it true: BGeo's own error bodies
     * carry a message, never the credential.
     */
    internal fun handleHttp(event: HttpEvent) {
        val data = JSONObject()
            .put("status", event.status)
            .put("success", event.success)
            .put("responseText", event.responseText)
        logUploader.logEvent(
            "onHttp",
            if (event.success) LogLevel.DEBUG else LogLevel.WARN,
            "${event.status} ${if (event.success) "ok" else "fail"}",
            data,
        )
    }

    internal fun handleConnectivityChange(event: ConnectivityChangeEvent) {
        logUploader.logEvent(
            "onConnectivityChange",
            if (event.connected) LogLevel.INFO else LogLevel.WARN,
            "connected=${event.connected}",
            JSONObject().put("connected", event.connected),
        )
    }
}

/** SDK [Location] -> the store's [Point], with the geofence fields an `onGeofence` transition adds. */
private fun Location.toPoint(event: String? = null, geofence: PointGeofence? = null): Point = Point(
    uuid = uuid,
    latitude = coords.latitude,
    longitude = coords.longitude,
    timestamp = timestamp,
    accuracy = coords.accuracy,
    speed = coords.speed,
    heading = coords.heading,
    odometer = odometer,
    activity = activity.type.wire,
    isMoving = isMoving,
    event = event ?: this.event,
    geofence = geofence,
)

/**
 * Root composable: the three tabs, plus the geofence form presented over them
 * (RN presents it as a modal route, iOS as a `.sheet` — this is the Compose
 * equivalent, with the same "long-press to add / tap a pin to edit" entry
 * points from `MapScreen`).
 */
@Composable
fun ExampleApp(container: AppContainer, permissionRequester: PermissionRequester) {
    var geofenceRequest by remember { mutableStateOf<GeofenceRequest?>(null) }

    ExampleScaffold { tab ->
        when (tab) {
            ExampleTab.MAP -> MapScreen(
                appStore = container.store,
                logUploader = container.logUploader,
                permissionRequester = permissionRequester,
                onGeofenceRequest = { geofenceRequest = it },
            )
            ExampleTab.LOGS -> LogsScreen(appStore = container.store)
            ExampleTab.SETTINGS -> SettingsScreen(
                appStore = container.store,
                configStore = container.configStore,
                deviceLink = container.deviceLink,
                logUploader = container.logUploader,
            )
        }
    }

    geofenceRequest?.let { request ->
        // A full-width `Dialog` is Compose's equivalent of RN's modal route /
        // iOS's `.sheet`: it draws over the tabs and the system back gesture
        // dismisses it, which is the form's only "cancel" — it has Save and
        // Delete buttons, no Cancel, exactly like both references.
        Dialog(
            onDismissRequest = { geofenceRequest = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                GeofenceFormScreen(
                    appStore = container.store,
                    geofences = container.geofences,
                    logUploader = container.logUploader,
                    request = request,
                    onDismiss = { geofenceRequest = null },
                )
            }
        }
    }
}
