package com.bgeo.sdk

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FacadeLifecycleTest {

    private lateinit var engine: FakeEngine

    @Before
    fun setUp() {
        engine = FakeEngine()
        BackgroundGeolocation.engine = engine
        BackgroundGeolocation.hub = EventHub()
        BackgroundGeolocation.attachEventHubAndResume()
    }

    @After
    fun tearDown() {
        // Restore the real defaults so a later test class (or a later run in
        // the same JVM, e.g. Gradle's test worker reuse) never sees a stale
        // FakeEngine left behind by this one.
        BackgroundGeolocation.engine = LiveEngine
        BackgroundGeolocation.hub = EventHub()
    }

    private fun sampleLocationJson(uuid: String = "sample-uuid"): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("timestamp", "2026-07-29T12:00:00.000Z")
        put("odometer", 12.3)
        put("coords", JSONObject().apply {
            put("latitude", 1.0)
            put("longitude", 2.0)
            put("accuracy", 5.0)
        })
        put("activity", JSONObject().apply {
            put("type", "still")
            put("confidence", 100)
        })
        put("battery", JSONObject().apply {
            put("level", 0.5)
            put("is_charging", false)
        })
    }

    // ---- attach: the bug the iOS facade shipped with -----------------------

    @Test
    fun `attach wires the hub so a launch-time event is buffered for a late subscriber`() {
        // Reproduce the state a genuinely untouched engine + hub pair would be
        // in, undoing setUp's attach: eventEmitter is nil and the hub has
        // never claimed it.
        val freshEngine = FakeEngine()
        BackgroundGeolocation.engine = freshEngine
        BackgroundGeolocation.hub = EventHub()

        BackgroundGeolocation.attachEventHubAndResume()

        // Emitted with no subscriber yet -- must be buffered, not dropped.
        freshEngine.emit("location", sampleLocationJson())

        var received: Location? = null
        BackgroundGeolocation.onLocation { received = it }

        assertEquals(
            "without attach() claiming the emitter, this event would have had nowhere to go and " +
                "would be lost forever instead of buffered",
            "sample-uuid",
            received?.uuid,
        )
    }

    @Test
    fun `loggerForegroundObserver flags foreground and flushes the backlog on start, clears the flag on stop`() {
        // DefaultLifecycleObserver needs no Android runtime -- only
        // ProcessLifecycleOwner.get() (in attach()) does -- so onStart/onStop
        // are callable directly against a stub LifecycleOwner never actually
        // dereferenced by the observer body.
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error("unused by setLoggerForeground/flushLogs")
        }

        BackgroundGeolocation.loggerForegroundObserver.onStart(owner)
        BackgroundGeolocation.loggerForegroundObserver.onStop(owner)

        assertEquals(listOf(true, false), engine.setLoggerForegroundCalls)
        assertEquals(
            "onStart must also drain the backlog accumulated while backgrounded -- " +
                "setLoggerForeground(true) alone only arms the flush for SUBSEQUENT lines",
            1,
            engine.flushLogsCallCount,
        )
    }

    // ---- ready --------------------------------------------------------------

    @Test
    fun `ready applies config then returns state`() = runTest {
        engine.stubbedStateMap = JSONObject().put("enabled", false)
        val state = BackgroundGeolocation.ready(Config(distanceFilter = 25.0))
        assertEquals(1, engine.appliedConfigs.size)
        assertEquals(25.0, engine.appliedConfigs.first()?.getDouble("distanceFilter"))
        assertFalse(state.enabled)
    }

    @Test
    fun `ready throws the engine's licence code`() = runTest {
        engine.stubbedLicenseErrorCode = "LICENSE_EXPIRED"
        try {
            BackgroundGeolocation.ready(Config())
            fail("expected a license error")
        } catch (e: BGeoException.LicenseExpired) {
            assertEquals("LICENSE_EXPIRED", e.code)
        }
    }

    @Test
    fun `ready applied the config before the licence check failed`() = runTest {
        // Order matters: the engine reads the license key out of the config.
        engine.stubbedLicenseErrorCode = "LICENSE_MISSING"
        try {
            BackgroundGeolocation.ready(Config(debug = true))
        } catch (e: BGeoException) {
            // expected
        }
        assertEquals(1, engine.appliedConfigs.size)
    }

    // ---- start / stop / setConfig licence gating ----------------------------

    @Test
    fun `start does not start tracking on a bad licence`() = runTest {
        engine.stubbedLicenseErrorCode = "LICENSE_APP_MISMATCH"
        try {
            BackgroundGeolocation.start()
        } catch (e: BGeoException) {
            // expected
        }
        assertEquals("tracking must not start on a bad license", 0, engine.startTrackingCallCount)
    }

    @Test
    fun `start starts tracking when licensed`() = runTest {
        engine.stubbedStateMap = JSONObject().put("enabled", true)
        val state = BackgroundGeolocation.start()
        assertEquals(1, engine.startTrackingCallCount)
        assertTrue(state.enabled)
    }

    @Test
    fun `stop never consults the licence`() = runTest {
        engine.stubbedLicenseErrorCode = "LICENSE_EXPIRED"
        engine.stubbedStateMap = JSONObject().put("enabled", false)
        BackgroundGeolocation.stop()
        assertEquals(1, engine.stopTrackingCallCount)
    }

    @Test
    fun `setConfig never consults the licence`() = runTest {
        engine.stubbedLicenseErrorCode = "LICENSE_EXPIRED"
        engine.stubbedStateMap = JSONObject().put("enabled", false)
        BackgroundGeolocation.setConfig(Config(debug = false))
        assertEquals(1, engine.appliedConfigs.size)
    }

    // ---- changePace -----------------------------------------------------------

    @Test
    fun `changePace throws Disabled when the engine refuses`() = runTest {
        engine.stubbedChangePaceResult = false
        try {
            BackgroundGeolocation.changePace(true)
            fail("expected DISABLED")
        } catch (e: BGeoException.Disabled) {
            assertEquals("DISABLED", e.code)
        }
    }

    @Test
    fun `changePace succeeds silently when the engine accepts`() = runTest {
        engine.stubbedChangePaceResult = true
        BackgroundGeolocation.changePace(true)
        assertEquals(listOf(true), engine.changePaceCalls)
    }

    // ---- getCurrentPosition / options ------------------------------------

    @Test
    fun `getCurrentPosition resolves a decoded Location`() = runTest {
        engine.stubbedCurrentPosition = FakeEngine.Outcome.success(sampleLocationJson())
        val location = BackgroundGeolocation.getCurrentPosition()
        assertEquals("sample-uuid", location.uuid)
    }

    @Test
    fun `getCurrentPosition forwards its options to the engine`() = runTest {
        // Without this, the method could pass null through to the engine and
        // every other test here would still pass -- this is the only proof
        // that e.g. `timeout` (SECONDS; the engine multiplies by 1000) reaches
        // the engine unmodified.
        engine.stubbedCurrentPosition = FakeEngine.Outcome.success(sampleLocationJson())
        BackgroundGeolocation.getCurrentPosition(CurrentPositionOptions(samples = 3, timeout = 10.0))
        assertEquals(1, engine.getCurrentPositionOptions.size)
        assertEquals(3, engine.getCurrentPositionOptions.first()?.getInt("samples"))
        assertEquals(10.0, engine.getCurrentPositionOptions.first()?.getDouble("timeout"))
    }

    @Test
    fun `getCurrentPosition throws the engine's rejection with its code and message intact`() = runTest {
        engine.stubbedCurrentPosition = FakeEngine.Outcome.failure("TIMEOUT", "no fix in 30s")
        try {
            BackgroundGeolocation.getCurrentPosition()
            fail("expected a rejection")
        } catch (e: BGeoException) {
            assertEquals("TIMEOUT", e.code)
            assertEquals("no fix in 30s", e.message)
        }
    }

    @Test
    fun `CurrentPositionOptions toJson omits unset values`() {
        assertEquals(0, CurrentPositionOptions().toJson().length())
        val json = CurrentPositionOptions(samples = 3, timeout = 10.0).toJson()
        assertEquals(2, json.length())
        assertEquals(3, json.getInt("samples"))
    }

    @Test
    fun `WatchPositionOptions toJson omits unset values`() {
        assertEquals(0, WatchPositionOptions().toJson().length())
        val json = WatchPositionOptions(interval = 5.0).toJson()
        assertEquals(1, json.length())
        assertEquals(5.0, json.getDouble("interval"), 0.0)
    }

    // ---- odometer -----------------------------------------------------------

    @Test
    fun `resetOdometer delegates to setOdometer(0_0)`() = runTest {
        engine.stubbedSetOdometer = FakeEngine.Outcome.success(sampleLocationJson())
        BackgroundGeolocation.resetOdometer()
        assertEquals(listOf(0.0), engine.setOdometerValues)
    }

    @Test
    fun `getOdometer returns the engine's reading`() = runTest {
        engine.stubbedOdometer = 42.0
        assertEquals(42.0, BackgroundGeolocation.getOdometer(), 0.0)
    }

    // ---- provider / power -----------------------------------------------------

    @Test
    fun `getProviderState decodes the engine's provider state`() = runTest {
        engine.stubbedProviderState = JSONObject().put("status", 3).put("enabled", true)
            .put("gps", true).put("network", true)
        val state = BackgroundGeolocation.getProviderState()
        assertEquals(AuthorizationStatus.ALWAYS, state.status)
        assertTrue(state.enabled)
    }

    @Test
    fun `isPowerSaveMode returns the engine's value`() = runTest {
        engine.stubbedIsPowerSaveMode = true
        assertTrue(BackgroundGeolocation.isPowerSaveMode())
    }

    // ---- watch ----------------------------------------------------------------

    @Test
    fun `watchPosition delegates options to the engine`() {
        BackgroundGeolocation.watchPosition(WatchPositionOptions(interval = 5.0))
        assertEquals(1, engine.startWatchOptions.size)
        assertEquals(5.0, engine.startWatchOptions.first()?.getDouble("interval"))
    }

    @Test
    fun `stopWatchPosition delegates to the engine`() {
        BackgroundGeolocation.stopWatchPosition()
        assertEquals(1, engine.stopWatchCallCount)
    }

    // ---- heading --------------------------------------------------------------

    @Test
    fun `watchHeading delegates options to the engine`() {
        BackgroundGeolocation.watchHeading(WatchHeadingOptions(smoothingTauMs = 250.0, minDeltaDeg = 2.0))
        assertEquals(1, engine.startHeadingOptions.size)
        val options = engine.startHeadingOptions.first()!!
        assertEquals(250.0, options.getDouble("smoothingTauMs"), 0.0001)
        assertEquals(2.0, options.getDouble("minDeltaDeg"), 0.0001)
        // An unset option is OMITTED, not sent as a null the engine would have
        // to defend against - its own default stands.
        assertFalse(options.has("minIntervalMs"))
    }

    @Test
    fun `watchHeading with no options sends an empty payload`() {
        BackgroundGeolocation.watchHeading()
        assertEquals(1, engine.startHeadingOptions.size)
        assertEquals(0, engine.startHeadingOptions.first()!!.length())
    }

    @Test
    fun `stopWatchingHeading delegates to the engine`() {
        BackgroundGeolocation.stopWatchingHeading()
        assertEquals(1, engine.stopHeadingCallCount)
    }

    // ---- events -----------------------------------------------------------

    @Test
    fun `onLocation delivers decoded locations`() {
        var received: Location? = null
        BackgroundGeolocation.onLocation { received = it }
        engine.emit("location", sampleLocationJson())
        assertEquals("sample-uuid", received?.uuid)
    }

    @Test
    fun `an undecodable payload is dropped, not crashed`() {
        var callCount = 0
        BackgroundGeolocation.onLocation { callCount++ }
        engine.emit("location", JSONObject().put("garbage", true))
        assertEquals(0, callCount)
    }

    @Test
    fun `onPowerSaveChange unwraps the bare boolean from isPowerSaveMode`() {
        var received: Boolean? = null
        BackgroundGeolocation.onPowerSaveChange { received = it }
        engine.emit("powersavechange", JSONObject().put("isPowerSaveMode", true))
        assertEquals(true, received)
    }

    @Test
    fun `onHeading delivers decoded heading events and drops undecodable ones`() {
        val received = mutableListOf<HeadingEvent>()
        BackgroundGeolocation.onHeading { received.add(it) }
        engine.emit(
            "heading",
            JSONObject().put("heading", 91.5).put("accuracy", 3).put("isTrue", true),
        )
        engine.emit("heading", JSONObject().put("accuracy", 3).put("isTrue", true))

        assertEquals(1, received.size)
        assertEquals(91.5, received.first().heading, 0.0001)
        assertEquals(3, received.first().accuracy)
        assertEquals(true, received.first().isTrue)
    }

    @Test
    fun `headingEvents Flow delivers a decoded event to a subscriber`() = runBlocking {
        val received = LinkedBlockingQueue<HeadingEvent>()
        val job = launch(Dispatchers.Default) {
            BackgroundGeolocation.headingEvents.collect { received.add(it) }
        }
        val deadline = System.currentTimeMillis() + 2_000
        while (BackgroundGeolocation.hub.subscriberCount("heading") == 0) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for a heading subscriber")
            Thread.sleep(5)
        }

        engine.emit(
            "heading",
            JSONObject().put("heading", 91.5).put("accuracy", 3).put("isTrue", true),
        )

        val event = received.poll(2, TimeUnit.SECONDS)
        assertEquals(91.5, event!!.heading, 0.0001)
        job.cancelAndJoin()
    }

    // ---- onLocationError / locationErrors (C1) -----------------------------
    //
    // Before this fix, a failing watchPosition (unlicensed build, or any
    // failing tick) had no callback, no throw and no reachable event -
    // `locationerror` had zero subscribers anywhere in the SDK.

    @Test
    fun `onLocationError decodes the license-gate shape`() {
        var received: BGeoException? = null
        BackgroundGeolocation.onLocationError { received = it }
        engine.emit(
            "locationerror",
            JSONObject().put("code", "LICENSE_EXPIRED").put("message", "Tracking is not licensed"),
        )
        assertTrue(received is BGeoException.LicenseExpired)
        assertEquals("LICENSE_EXPIRED", received?.code)
    }

    @Test
    fun `onLocationError decodes the watchTick shape`() {
        var received: BGeoException? = null
        BackgroundGeolocation.onLocationError { received = it }
        engine.emit(
            "locationerror",
            JSONObject().put("code", "408").put("message", "Location request timed out"),
        )
        assertTrue(received is BGeoException.Unknown)
        assertEquals("408", received?.code)
        assertEquals("Location request timed out", received?.message)
    }

    @Test
    fun `an undecodable locationerror payload is dropped, not crashed`() {
        var callCount = 0
        BackgroundGeolocation.onLocationError { callCount++ }
        engine.emit("locationerror", JSONObject().put("garbage", true))
        assertEquals(0, callCount)
    }

    /**
     * End to end: proves the event actually reaches a subscriber through
     * [BackgroundGeolocation.locationErrors] rather than being dropped -
     * the exact failure this finding exists to close (`locationerror` was
     * previously unreachable from any public API, `hub`/`EventHub` being
     * `internal`).
     */
    @Test
    fun `locationErrors Flow delivers a decoded exception to a subscriber`() = runBlocking {
        val received = LinkedBlockingQueue<BGeoException>()
        val job = launch(Dispatchers.Default) {
            BackgroundGeolocation.locationErrors.collect { received.add(it) }
        }
        val deadline = System.currentTimeMillis() + 2_000
        while (BackgroundGeolocation.hub.subscriberCount("locationerror") == 0) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for a locationerror subscriber")
            Thread.sleep(5)
        }

        engine.emit(
            "locationerror",
            JSONObject().put("code", "LICENSE_EXPIRED").put("message", "Tracking is not licensed"),
        )

        val error = received.poll(2, TimeUnit.SECONDS)
        assertTrue(
            "the locationerror event must reach a Flow subscriber, not be dropped in total silence",
            error is BGeoException.LicenseExpired,
        )
        job.cancelAndJoin()
    }

    @Test
    fun `removeListeners detaches every subscriber`() {
        var callCount = 0
        BackgroundGeolocation.onLocation { callCount++ }
        BackgroundGeolocation.removeListeners()
        engine.emit("location", sampleLocationJson())
        assertEquals(0, callCount)
    }

    // ---- awaitCallback / CallbackBridge --------------------------------------

    @Test
    fun `awaitCallback survives a callback that fires twice`() = runTest {
        // The engine's contract is single-shot, but a double resume would throw
        // IllegalStateException and take down the caller's coroutine.
        val result = awaitCallback { callback ->
            callback.success(JSONObject().put("ok", true))
            callback.success(JSONObject().put("ok", false))
        }
        assertEquals(true, result!!.getBoolean("ok"))
    }

    @Test
    fun `awaitCallback propagates the engine's error as a BGeoException`() = runTest {
        try {
            awaitCallback { callback -> callback.error("NOT_FOUND", "no such record") }
            fail("expected a BGeoException")
        } catch (e: BGeoException) {
            assertEquals("NOT_FOUND", e.code)
            assertEquals("no such record", e.message)
        }
    }
}
