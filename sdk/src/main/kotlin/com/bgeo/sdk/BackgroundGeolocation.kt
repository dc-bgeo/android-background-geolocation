package com.bgeo.sdk

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONObject

/**
 * The public BGeo API. Method and event names mirror
 * `react-native/src/index.ts` so a developer moving between SDKs finds the
 * same vocabulary.
 *
 * Every method that isn't a pure getter goes through [engine]/[hub] — never
 * `LiveEngine` directly — so the whole surface is testable against
 * `FakeEngine` without a device.
 */
object BackgroundGeolocation {

    /** Test seam: the real engine in production, swapped for `FakeEngine` in tests. */
    internal var engine: Engine = LiveEngine

    /**
     * Test seam. Deliberately NOT attached to [engine] until [attach] (or, in
     * a test, [attachEventHubAndResume]) runs — see the warning on [attach].
     */
    internal var hub: EventHub = EventHub()

    /**
     * Wire the SDK to the process. Call from `Application.onCreate()` — the
     * system restarts this process for boot, geofence and service events, and
     * `Application.onCreate` is the only hook that runs in every one of them.
     *
     * Order matters: [Engine.init] first (the engine needs a `Context` before
     * anything else), THEN [attachEventHubAndResume] claims the engine's
     * `eventEmitter` slot and only THEN resumes tracking — attaching the hub
     * before resuming means a location emitted the instant tracking resumes
     * is buffered rather than lost. Skipping the hub-attach step here is
     * exactly the bug the iOS facade shipped with: `eventEmitter` stayed null
     * and the engine silently discarded every event until an app happened to
     * subscribe, making the whole 64-event launch buffer dead code.
     */
    fun attach(context: Context) {
        engine.init(context)
        attachEventHubAndResume()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // App foreground is one of the log-flush piggyback triggers: while
            // foreground, the logger also debounce-flushes each line so a
            // watching console is near-realtime (BGGeoLogger.foreground gates a
            // 3s trailing-edge flush) — mirrors
            // BackgroundGeolocationModule.kt:49,54 / BGeoFlutterPlugin.kt:56,59.
            // Without this, an app-logged line waits for the next heartbeat
            // before reaching the web console.
            override fun onStart(owner: LifecycleOwner) {
                engine.setLoggerForeground(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                engine.setLoggerForeground(false)
            }
        })
    }

    /**
     * The `Context`-free half of [attach]: claims the emitter and resumes
     * tracking. Split out so it's reachable from a pure-JVM unit test — a
     * real `Context`/`ProcessLifecycleOwner` cannot be constructed under this
     * module's `unitTests.isReturnDefaultValues` (no Robolectric). See
     * `FacadeLifecycleTest`'s attach coverage.
     */
    internal fun attachEventHubAndResume() {
        hub.attach(engine)
        engine.resumeTrackingIfEnabled()
    }

    // ---- lifecycle ------------------------------------------------------

    /**
     * Applies [config] FIRST, then checks the licence — the engine reads the
     * licence key out of the config it was just handed, so a bad licence
     * still leaves [config] applied (`BackgroundGeolocationModule.kt:109-123`).
     */
    suspend fun ready(config: Config): State {
        engine.applyConfig(config.toJson())
        engine.licenseErrorCode()?.let { throw licenseError(it) }
        return currentState()
    }

    /**
     * Applies [config] and returns state. Unlike [ready]/[start], this never
     * consults the licence (`BackgroundGeolocationModule.kt:125-128`).
     */
    suspend fun setConfig(config: Config): State {
        engine.applyConfig(config.toJson())
        return currentState()
    }

    /** Checks the licence BEFORE starting tracking (`BackgroundGeolocationModule.kt:130-137`). */
    suspend fun start(): State {
        engine.licenseErrorCode()?.let { throw licenseError(it) }
        engine.startTracking()
        return currentState()
    }

    /** Never consults the licence (`BackgroundGeolocationModule.kt:139-142`). */
    suspend fun stop(): State {
        engine.stopTracking()
        return currentState()
    }

    suspend fun getState(): State = currentState()

    /** Throws [BGeoException.Disabled] when the engine refuses (tracking is off). */
    suspend fun changePace(isMoving: Boolean) {
        if (!engine.changePace(isMoving)) {
            throw BGeoException.Disabled("Cannot changePace while tracking is disabled")
        }
    }

    // ---- single-shot / watch --------------------------------------------

    /**
     * Resolves a single fix. [CurrentPositionOptions.timeout] is in SECONDS
     * (default 30) — the engine multiplies it by 1000 internally to get
     * milliseconds.
     */
    suspend fun getCurrentPosition(options: CurrentPositionOptions = CurrentPositionOptions()): Location {
        val json = awaitCallback { callback -> engine.getCurrentPosition(options.toJson(), callback) }
        return decodeLocationOrThrow(json)
    }

    /**
     * Watch fixes are ordinary `location` events carrying `extras.watch` —
     * there is no separate channel; subscribe via [locations]/[onLocation] as
     * normal (`react-native/src/index.ts:167-173`).
     */
    fun watchPosition(options: WatchPositionOptions = WatchPositionOptions()) {
        engine.startWatch(options.toJson())
    }

    fun stopWatchPosition() {
        engine.stopWatch()
    }

    // ---- provider / power -------------------------------------------------

    suspend fun getProviderState(): ProviderState = ProviderState.from(engine.providerState())!!

    /** Current OS battery-saver state. */
    suspend fun isPowerSaveMode(): Boolean = engine.isPowerSaveMode()

    // ---- odometer -----------------------------------------------------------

    /** Current odometer reading in metres. */
    suspend fun getOdometer(): Double = engine.currentOdometer()

    suspend fun setOdometer(value: Double): Location {
        val json = awaitCallback { callback -> engine.setOdometer(value, callback) }
        return decodeLocationOrThrow(json)
    }

    /** `resetOdometer` is `setOdometer(0.0)`, not a distinct engine call. */
    suspend fun resetOdometer(): Location = setOdometer(0.0)

    // ---- event streams --------------------------------------------------
    //
    // Each access mints a NEW subscription (EventHub.flow subscribes on
    // collection) — bind the result to a `val` rather than re-reading the
    // property mid-collection, or a second, independent subscription starts.
    // Decoding rule, same as the callback-style subscriptions below: a
    // payload that fails to decode is DROPPED, never crashed, never
    // delivered half-built.

    val locations: Flow<Location> get() = hub.flow("location").mapNotNull(Location::from)
    val motionChanges: Flow<MotionChangeEvent> get() = hub.flow("motionchange").mapNotNull(MotionChangeEvent::from)
    val providerChanges: Flow<ProviderChangeEvent> get() = hub.flow("providerchange").mapNotNull(ProviderChangeEvent::from)
    val heartbeats: Flow<HeartbeatEvent> get() = hub.flow("heartbeat").mapNotNull(HeartbeatEvent::from)
    val httpEvents: Flow<HttpEvent> get() = hub.flow("http").mapNotNull(HttpEvent::from)
    val connectivityChanges: Flow<ConnectivityChangeEvent> get() = hub.flow("connectivitychange").mapNotNull(ConnectivityChangeEvent::from)
    val powerSaveChanges: Flow<Boolean> get() = hub.flow("powersavechange").mapNotNull { it.boolOrNull("isPowerSaveMode") }
    val authorizationEvents: Flow<JSONObject> get() = hub.flow("authorization")

    // ---- callback-style subscriptions -------------------------------------

    fun onLocation(handler: (Location) -> Unit): Subscription =
        hub.subscribe("location") { json -> Location.from(json)?.let(handler) }

    fun onMotionChange(handler: (MotionChangeEvent) -> Unit): Subscription =
        hub.subscribe("motionchange") { json -> MotionChangeEvent.from(json)?.let(handler) }

    fun onProviderChange(handler: (ProviderChangeEvent) -> Unit): Subscription =
        hub.subscribe("providerchange") { json -> ProviderChangeEvent.from(json)?.let(handler) }

    fun onHeartbeat(handler: (HeartbeatEvent) -> Unit): Subscription =
        hub.subscribe("heartbeat") { json -> HeartbeatEvent.from(json)?.let(handler) }

    fun onHttp(handler: (HttpEvent) -> Unit): Subscription =
        hub.subscribe("http") { json -> HttpEvent.from(json)?.let(handler) }

    fun onConnectivityChange(handler: (ConnectivityChangeEvent) -> Unit): Subscription =
        hub.subscribe("connectivitychange") { json -> ConnectivityChangeEvent.from(json)?.let(handler) }

    /** Native emits `{isPowerSaveMode}`; this unwraps to the bare boolean. */
    fun onPowerSaveChange(handler: (Boolean) -> Unit): Subscription =
        hub.subscribe("powersavechange") { json -> json.boolOrNull("isPowerSaveMode")?.let(handler) }

    fun onAuthorization(handler: (JSONObject) -> Unit): Subscription =
        hub.subscribe("authorization", handler)

    fun removeListeners() {
        hub.removeAll()
    }

    // ---- private helpers ----------------------------------------------------

    private fun licenseError(code: String): BGeoException =
        BGeoException.from(code, "BGeo license check failed ($code)")

    private fun currentState(): State = State.from(engine.stateMap())!!

    private fun decodeLocationOrThrow(json: JSONObject?): Location =
        json?.let(Location::from) ?: throw BGeoException.Unknown("DECODE_ERROR", "Failed to decode location")
}

/** Options for [BackgroundGeolocation.getCurrentPosition]. */
data class CurrentPositionOptions(
    val persist: Boolean? = null,
    val samples: Int? = null,
    /** SECONDS (default 30) — the engine multiplies this by 1000 internally to get milliseconds. */
    val timeout: Double? = null,
    val maximumAge: Double? = null,
    val desiredAccuracy: Int? = null,
    val extras: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        persist?.let { put("persist", it) }
        samples?.let { put("samples", it) }
        timeout?.let { put("timeout", it) }
        maximumAge?.let { put("maximumAge", it) }
        desiredAccuracy?.let { put("desiredAccuracy", it) }
        extras?.let { put("extras", it) }
    }
}

/** Options for [BackgroundGeolocation.watchPosition]. */
data class WatchPositionOptions(
    val interval: Double? = null,
    val desiredAccuracy: Int? = null,
    val persist: Boolean? = null,
    val extras: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        interval?.let { put("interval", it) }
        desiredAccuracy?.let { put("desiredAccuracy", it) }
        persist?.let { put("persist", it) }
        extras?.let { put("extras", it) }
    }
}
