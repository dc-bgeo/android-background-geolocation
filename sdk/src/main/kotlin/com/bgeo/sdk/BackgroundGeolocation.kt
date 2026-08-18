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
     * The process-lifecycle fan-out. Two independent engine flags ride this
     * one observer, because on Android nothing else tells the engine the app
     * moved:
     *
     * 1. **Logger foreground.** While foreground, the logger also
     *    debounce-flushes each line so a watching console is near-realtime
     *    (`BGGeoLogger.foreground` gates a 3s trailing-edge flush for
     *    SUBSEQUENT lines) — mirrors `BackgroundGeolocationModule.kt:49,54` /
     *    `BGeoFlutterPlugin.kt:56,59`. [Engine.flushLogs] additionally drains
     *    whatever backlog accumulated while backgrounded — the flag alone does
     *    nothing for lines already written, and that backlog is exactly what a
     *    developer opens the web console to read (same pairing as
     *    `BackgroundGeolocationModule.kt:49-50` / `BGeoFlutterPlugin.kt:56-57`).
     * 2. **Engine app foreground.** [Engine.setAppForeground] is the engine's
     *    ONLY pause/resume trigger for an active [watchHeading] compass. Miss
     *    it and a `watchHeading` session keeps a 50 Hz sensor subscription
     *    alive forever after the user presses Home — the exact opposite of the
     *    foreground-only contract [watchHeading] documents. iOS needs no
     *    equivalent: its engine self-observes `UIApplication` notifications.
     *
     * Extracted from [attach] into a standalone property (rather than an
     * anonymous object built inline) so it's unit-testable: `DefaultLifecycleObserver`
     * needs no Android runtime, only `ProcessLifecycleOwner` does — calling
     * `onStart`/`onStop` directly against a stub `LifecycleOwner` exercises the
     * real logic without a device. See `FacadeLifecycleTest`.
     */
    internal val processForegroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            engine.setLoggerForeground(true)
            engine.setAppForeground(true)
            engine.flushLogs()
        }
        override fun onStop(owner: LifecycleOwner) {
            engine.setLoggerForeground(false)
            engine.setAppForeground(false)
        }
    }

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(processForegroundObserver)
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

    // ---- compass heading --------------------------------------------------

    /**
     * Arms the compass. Each admitted sample arrives on its own channel —
     * subscribe via [headingEvents]/[onHeading]; heading is NOT part of the
     * `location` payload.
     *
     * Unlike [crashEvents], this feed is explicitly started (like
     * [watchPosition]) and needs no config flag. Calling it a second time
     * RESTARTS the session with the new tuning — no smoothing or emission
     * state survives. It is a silent no-op on a device with no usable
     * magnetometer.
     *
     * **Foreground-only.** The engine unregisters the sensor listeners
     * whenever the app goes to the background and re-registers them on return
     * to the foreground — a 50 Hz sensor subscription behind a backgrounded
     * app is pure battery burn. This is automatic and needs nothing from you,
     * but it does mean [headingEvents] goes quiet for as long as the app is
     * backgrounded; that is expected, not a fault. The smoothing window is
     * reset across the pause, so the first sample after returning seeds fresh
     * rather than easing out of a heading the device left minutes ago. Arming
     * while already backgrounded is fine — the session starts subscribed to
     * nothing and begins delivering at the next foreground.
     *
     * **Re-arm after a restart.** [stop] tears the heading feed down on BOTH
     * platforms, so a [stop]/[start] cycle leaves the compass off until you
     * call this again. Tracking is otherwise orthogonal to the compass: it
     * needs no location permission and runs without [start] — but "no [start]
     * needed" is not "always on", see the foreground note above.
     */
    fun watchHeading(options: WatchHeadingOptions = WatchHeadingOptions()) {
        engine.startHeading(options.toJson())
    }

    /**
     * Disarms the compass. Safe to call when none is running.
     *
     * The engine's sensor thread is retired asynchronously (`quitSafely`), so
     * ONE already-queued heading event may still reach subscribers momentarily
     * after this returns. Drop your subscription, don't assume the last event
     * has landed.
     */
    fun stopWatchHeading() {
        engine.stopHeading()
    }

    // ---- provider / power -------------------------------------------------

    /**
     * Runs the escalating permission flow (`PermissionPlan`'s FINE/COARSE ->
     * BACKGROUND -> ACTIVITY_RECOGNITION order) through [requester]. On iOS
     * this lives inside the engine; on Android it needs an `Activity`, which
     * [requester] supplies.
     */
    suspend fun requestPermission(requester: PermissionRequester): AuthorizationStatus = requester.request()

    suspend fun getProviderState(): ProviderState = ProviderState.from(engine.providerState())

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
    //
    // Delivery contract: an `onX` callback (below) is invoked inline on
    // whatever thread the engine emitted from and is never dropped — but a
    // slow handler stalls that engine thread until it returns. A `Flow`
    // property is the opposite trade: it buffers without limit
    // (`EventHub.flow`), so a slow collector never blocks the emitter and
    // never silently loses an event either, but an indefinitely slow or
    // stalled collector lets that buffer grow without bound. Prefer a `Flow`
    // when you want the emitting thread left alone; prefer `onX` when you
    // want backpressure instead of unbounded memory growth.

    val locations: Flow<Location> get() = hub.flow("location").mapNotNull(Location::from)
    val motionChanges: Flow<MotionChangeEvent> get() = hub.flow("motionchange").mapNotNull(MotionChangeEvent::from)
    val providerChanges: Flow<ProviderChangeEvent> get() = hub.flow("providerchange").mapNotNull(ProviderChangeEvent::from)
    val heartbeats: Flow<HeartbeatEvent> get() = hub.flow("heartbeat").mapNotNull(HeartbeatEvent::from)
    val httpEvents: Flow<HttpEvent> get() = hub.flow("http").mapNotNull(HttpEvent::from)
    val connectivityChanges: Flow<ConnectivityChangeEvent> get() = hub.flow("connectivitychange").mapNotNull(ConnectivityChangeEvent::from)
    val powerSaveChanges: Flow<Boolean> get() = hub.flow("powersavechange").mapNotNull { it.boolOrNull("isPowerSaveMode") }
    val authorizationEvents: Flow<JSONObject> get() = hub.flow("authorization")

    /**
     * Crash detection. Only armed while moving, and off by default
     * (`crashDetection.enabled`) - see [Config.crashDetection].
     */
    val crashEvents: Flow<CrashEvent> get() = hub.flow("crash").mapNotNull(CrashEvent::from)

    /**
     * Compass samples. Silent until [watchHeading] arms the feed, silent again
     * after [stop], which tears it down (re-arm with [watchHeading]) — and
     * silent for as long as the app is BACKGROUNDED, which is by design: the
     * engine parks the sensors on the way out and resumes them on the way back
     * in. See [watchHeading] for the whole picture before filing background
     * silence as a bug.
     */
    val headingEvents: Flow<HeadingEvent> get() = hub.flow("heading").mapNotNull(HeadingEvent::from)

    /**
     * Every `locationerror` the engine emits — a failing [watchPosition] tick,
     * or [watchPosition] itself called on an unlicensed build (both sites
     * short-circuit with this event, no callback and no throw). Without a
     * subscriber here, a failing watch fails in total silence.
     */
    val locationErrors: Flow<BGeoException> get() = hub.flow("locationerror").mapNotNull(BGeoException::fromLocationErrorEvent)

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

    /** Callback-style twin of [crashEvents]. */
    fun onCrash(handler: (CrashEvent) -> Unit): Subscription =
        hub.subscribe("crash") { json -> CrashEvent.from(json)?.let(handler) }

    /**
     * Callback-style twin of [headingEvents] — including its silences: no
     * samples arrive while the app is backgrounded (see [watchHeading]).
     */
    fun onHeading(handler: (HeadingEvent) -> Unit): Subscription =
        hub.subscribe("heading") { json -> HeadingEvent.from(json)?.let(handler) }

    /** See [locationErrors] — the callback-style twin of the same event. */
    fun onLocationError(handler: (BGeoException) -> Unit): Subscription =
        hub.subscribe("locationerror") { json -> BGeoException.fromLocationErrorEvent(json)?.let(handler) }

    /**
     * Detaches every subscriber registered via [onLocation] and its siblings,
     * including a [locationErrors]/[locations]-style `Flow`'s subscription —
     * but NOT the `Flow`'s collector itself: each `Flow` access mints its own
     * `callbackFlow`-backed channel ([EventHub.flow]), and this only clears
     * [EventHub]'s subscriber list. A collector already running when this is
     * called stays suspended forever, receiving nothing further, until its
     * own coroutine/scope is cancelled — cancel that collection directly
     * rather than relying on this call to stop it.
     */
    fun removeListeners() {
        hub.removeAll()
    }

    // ---- private helpers ----------------------------------------------------

    private fun licenseError(code: String): BGeoException =
        BGeoException.from(code, "BGeo license check failed ($code)")

    private fun currentState(): State = State.from(engine.stateMap())

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

/**
 * Options for [BackgroundGeolocation.watchHeading]. Every field is optional
 * and tunes the engine's shared heading policy; leaving one `null` omits it
 * from the payload so the engine's own default applies.
 *
 * @property smoothingTauMs time constant of the exponential azimuth smoother,
 *   in milliseconds. Larger is steadier and laggier.
 * @property minIntervalMs floor on the interval between two emitted events,
 *   in milliseconds.
 * @property minDeltaDeg floor on the change in heading, in DEGREES, needed to
 *   emit before [minIntervalMs] has elapsed.
 */
data class WatchHeadingOptions(
    val smoothingTauMs: Double? = null,
    val minIntervalMs: Double? = null,
    val minDeltaDeg: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        smoothingTauMs?.let { put("smoothingTauMs", it) }
        minIntervalMs?.let { put("minIntervalMs", it) }
        minDeltaDeg?.let { put("minDeltaDeg", it) }
    }
}
