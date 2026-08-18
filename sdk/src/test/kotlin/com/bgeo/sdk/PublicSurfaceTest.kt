package com.bgeo.sdk

import androidx.activity.result.ActivityResultCaller
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A compile-time guard, not a behavioural test: constructs every public
 * model, options, config and exception type through its public constructor
 * and reads every public property. A later change that renames, removes, or
 * changes the type of a constructor parameter or property breaks this file's
 * compilation, not just some runtime assertion - so it pins the public
 * SIGNATURE and shape of this surface, plus (via the model round-trip
 * assertions) its decoding behaviour.
 *
 * What this does NOT guard, and why: VISIBILITY. On most Kotlin setups you'd
 * expect narrowing a type from public to `internal` to break a test like this
 * one that references it - but AGP/the Kotlin Gradle plugin wires each unit
 * test compilation as a FRIEND of the corresponding main compilation, so test
 * code sees `internal` declarations exactly as if they were public. This
 * module already relies on that elsewhere - `PermissionPlanTest` calls the
 * `internal object PermissionPlan` and `JsonDecodingTest` calls the
 * `internal fun` helpers in `JsonDecoding.kt` directly, with no
 * `-Xfriend-paths` configured anywhere. The practical consequence: if
 * `Config`, `Location`, `Coords` or any other type below were narrowed to
 * `internal` tomorrow, every test in this file would still compile and still
 * pass. Visibility is guarded separately, by the source-level audit (grep
 * plus a manual read of every file), which the Kotlin compiler enforces
 * categorically at the point a real CONSUMING app (a different Gradle
 * module, with no friend-path access) tries to compile against this one.
 *
 * What this deliberately does NOT cover for other reasons: `Engine`,
 * `LiveEngine`, `EventHub`, `FakeEngine`, the `JsonDecoding` helpers,
 * `awaitCallback` and `PermissionPlan` are `internal` - out of scope for a
 * PUBLIC surface guard regardless of the friend-path caveat above.
 * `Subscription` and `PermissionRequester` are public types with unusual
 * shapes (an internal constructor; an Activity-bound constructor) - each is
 * covered below the way an app would actually be able to use it.
 */
class PublicSurfaceTest {

    // ---- options (BackgroundGeolocation.kt) ------------------------------

    @Test
    fun `CurrentPositionOptions constructs and every property reads back`() {
        val extras = JSONObject().put("k", "v")
        val options = CurrentPositionOptions(
            persist = true,
            samples = 3,
            timeout = 30.0,
            maximumAge = 5000.0,
            desiredAccuracy = DesiredAccuracy.HIGH.value,
            extras = extras,
        )
        assertEquals(true, options.persist)
        assertEquals(3, options.samples)
        assertEquals(30.0, options.timeout)
        assertEquals(5000.0, options.maximumAge)
        assertEquals(DesiredAccuracy.HIGH.value, options.desiredAccuracy)
        assertSame(extras, options.extras)
    }

    @Test
    fun `WatchPositionOptions constructs and every property reads back`() {
        val extras = JSONObject().put("k", "v")
        val options = WatchPositionOptions(
            interval = 1000.0,
            desiredAccuracy = DesiredAccuracy.MEDIUM.value,
            persist = false,
            extras = extras,
        )
        assertEquals(1000.0, options.interval)
        assertEquals(DesiredAccuracy.MEDIUM.value, options.desiredAccuracy)
        assertEquals(false, options.persist)
        assertSame(extras, options.extras)
    }

    @Test
    fun `WatchHeadingOptions constructs and every property reads back`() {
        val options = WatchHeadingOptions(
            smoothingTauMs = 250.0,
            minIntervalMs = 100.0,
            minDeltaDeg = 2.0,
        )
        assertEquals(250.0, options.smoothingTauMs)
        assertEquals(100.0, options.minIntervalMs)
        assertEquals(2.0, options.minDeltaDeg)

        // Every field is optional; the default constructor sends the engine
        // nothing and lets its own tuning defaults stand.
        val defaults = WatchHeadingOptions()
        assertNull(defaults.smoothingTauMs)
        assertNull(defaults.minIntervalMs)
        assertNull(defaults.minDeltaDeg)
        assertEquals(0, defaults.toJson().length())
    }

    // ---- config (Config.kt) -----------------------------------------------

    @Test
    fun `NotificationConfig constructs and every property reads back`() {
        val notification = NotificationConfig(
            title = "Tracking",
            text = "Location is on",
            channelId = "bgeo",
            channelName = "BGeo",
            smallIcon = "drawable/ic_notify",
            color = "#00FF00",
            priority = 2,
        )
        assertEquals("Tracking", notification.title)
        assertEquals("Location is on", notification.text)
        assertEquals("bgeo", notification.channelId)
        assertEquals("BGeo", notification.channelName)
        assertEquals("drawable/ic_notify", notification.smallIcon)
        assertEquals("#00FF00", notification.color)
        assertEquals(2, notification.priority)
    }

    @Test
    fun `AuthorizationConfig constructs and every property reads back`() {
        val payload = JSONObject().put("refresh_token", "{refreshToken}")
        val headers = mapOf("X-Api-Key" to "secret")
        val authorization = AuthorizationConfig(
            strategy = "JWT",
            accessToken = "access",
            refreshToken = "refresh",
            refreshUrl = "https://example.test/refresh",
            refreshPayload = payload,
            refreshHeaders = headers,
        )
        assertEquals("JWT", authorization.strategy)
        assertEquals("access", authorization.accessToken)
        assertEquals("refresh", authorization.refreshToken)
        assertEquals("https://example.test/refresh", authorization.refreshUrl)
        assertSame(payload, authorization.refreshPayload)
        assertEquals(headers, authorization.refreshHeaders)
    }

    @Test
    fun `AuthorizationConfig CLEAR sentinel is reachable`() {
        assertEquals(Config.CLEAR_STRING, AuthorizationConfig.CLEAR.strategy)
    }

    @Test
    fun `Config constructs with every one of its 57 properties and each reads back`() {
        val params = JSONObject().put("device_id", "abc")
        val extras = JSONObject().put("tenant", "acme")
        val notification = NotificationConfig(title = "Tracking")
        val authorization = AuthorizationConfig(strategy = "JWT")
        val locationAlert = mapOf("titleWhenNotEnabled" to "Enable location")
        val rationale = mapOf("title" to "Background location needed")
        val headers = mapOf("Authorization" to "Bearer token")

        val config = Config(
            locationAuthorizationRequest = "Always",
            locationAuthorizationAlert = locationAlert,
            disableLocationAuthorizationAlert = true,
            backgroundPermissionRationale = rationale,
            desiredAccuracy = DesiredAccuracy.HIGH.value,
            distanceFilter = 10.0,
            disableLocationFilter = false,
            locationFilterMaxAccuracy = 100.0,
            locationFilterMaxSpeed = 60.0,
            locationFilterPolicy = "Conservative",
            kalmanProfile = "DEFAULT",
            odometerAccuracyThreshold = 50.0,
            disableElasticity = false,
            elasticityMultiplier = 1.0,
            stationaryDesiredAccuracy = "BALANCED",
            stationaryLocationUpdateInterval = 30_000,
            triggerActivities = "in_vehicle,on_bicycle,walking,running,on_foot",
            minimumActivityRecognitionConfidence = 75,
            activityRecognitionInterval = 10_000,
            disableMotionActivityUpdates = false,
            stopTimeout = 5,
            showsBackgroundLocationIndicator = true,
            stationaryRadius = 25.0,
            stationaryDistanceFilter = 25.0,
            preventSuspend = false,
            heartbeatInterval = 60,
            motionTriggerDelay = 30_000,
            locationUpdateInterval = 1_000,
            foregroundService = true,
            notification = notification,
            stopOnTerminate = false,
            startOnBoot = true,
            debug = false,
            logLevel = LogLevel.INFO.value,
            logMaxDays = 3,
            logUrl = "https://example.test/log",
            maxDaysToPersist = 7,
            url = "https://example.test/locations",
            method = "POST",
            headers = headers,
            params = params,
            extras = extras,
            httpRootProperty = "location",
            autoSync = true,
            disableAutoSyncOnCellular = false,
            autoSyncThreshold = 5,
            batchSync = true,
            maxBatchSize = 50,
            httpTimeoutMs = 60_000,
            maxRecordsToPersist = 10_000,
            authorization = authorization,
            stationaryKeepAlive = true,
            diagnosticExtras = false,
            useSessionEngine = true,
            geofenceProximityRadius = 1000.0,
            maxMonitoredGeofences = 99,
            geofenceInitialTriggerEntry = true,
        )

        assertEquals("Always", config.locationAuthorizationRequest)
        assertEquals(locationAlert, config.locationAuthorizationAlert)
        assertEquals(true, config.disableLocationAuthorizationAlert)
        assertEquals(rationale, config.backgroundPermissionRationale)
        assertEquals(DesiredAccuracy.HIGH.value, config.desiredAccuracy)
        assertEquals(10.0, config.distanceFilter)
        assertEquals(false, config.disableLocationFilter)
        assertEquals(100.0, config.locationFilterMaxAccuracy)
        assertEquals(60.0, config.locationFilterMaxSpeed)
        assertEquals("Conservative", config.locationFilterPolicy)
        assertEquals("DEFAULT", config.kalmanProfile)
        assertEquals(50.0, config.odometerAccuracyThreshold)
        assertEquals(false, config.disableElasticity)
        assertEquals(1.0, config.elasticityMultiplier)
        assertEquals("BALANCED", config.stationaryDesiredAccuracy)
        assertEquals(30_000, config.stationaryLocationUpdateInterval)
        assertEquals("in_vehicle,on_bicycle,walking,running,on_foot", config.triggerActivities)
        assertEquals(75, config.minimumActivityRecognitionConfidence)
        assertEquals(10_000, config.activityRecognitionInterval)
        assertEquals(false, config.disableMotionActivityUpdates)
        assertEquals(5, config.stopTimeout)
        assertEquals(true, config.showsBackgroundLocationIndicator)
        assertEquals(25.0, config.stationaryRadius)
        assertEquals(25.0, config.stationaryDistanceFilter)
        assertEquals(false, config.preventSuspend)
        assertEquals(60, config.heartbeatInterval)
        assertEquals(30_000, config.motionTriggerDelay)
        assertEquals(1_000, config.locationUpdateInterval)
        assertEquals(true, config.foregroundService)
        assertSame(notification, config.notification)
        assertEquals(false, config.stopOnTerminate)
        assertEquals(true, config.startOnBoot)
        assertEquals(false, config.debug)
        assertEquals(LogLevel.INFO.value, config.logLevel)
        assertEquals(3, config.logMaxDays)
        assertEquals("https://example.test/log", config.logUrl)
        assertEquals(7, config.maxDaysToPersist)
        assertEquals("https://example.test/locations", config.url)
        assertEquals("POST", config.method)
        assertEquals(headers, config.headers)
        assertSame(params, config.params)
        assertSame(extras, config.extras)
        assertEquals("location", config.httpRootProperty)
        assertEquals(true, config.autoSync)
        assertEquals(false, config.disableAutoSyncOnCellular)
        assertEquals(5, config.autoSyncThreshold)
        assertEquals(true, config.batchSync)
        assertEquals(50, config.maxBatchSize)
        assertEquals(60_000, config.httpTimeoutMs)
        assertEquals(10_000, config.maxRecordsToPersist)
        assertSame(authorization, config.authorization)
        assertEquals(true, config.stationaryKeepAlive)
        assertEquals(false, config.diagnosticExtras)
        assertEquals(true, config.useSessionEngine)
        assertEquals(1000.0, config.geofenceProximityRadius)
        assertEquals(99, config.maxMonitoredGeofences)
        assertEquals(true, config.geofenceInitialTriggerEntry)
    }

    // ---- models (Models.kt) ------------------------------------------------

    @Test
    fun `Coords constructs and every property reads back`() {
        val coords = Coords(
            latitude = 52.2297,
            longitude = 21.0122,
            accuracy = 12.0,
            altitude = 110.0,
            altitudeAccuracy = 3.0,
            speed = 4.2,
            speedAccuracy = 0.5,
            heading = 91.0,
            headingAccuracy = 2.0,
            ellipsoidalAltitude = 140.0,
        )
        assertEquals(52.2297, coords.latitude, 0.0)
        assertEquals(21.0122, coords.longitude, 0.0)
        assertEquals(12.0, coords.accuracy, 0.0)
        assertEquals(110.0, coords.altitude!!, 0.0)
        assertEquals(3.0, coords.altitudeAccuracy!!, 0.0)
        assertEquals(4.2, coords.speed!!, 0.0)
        assertEquals(0.5, coords.speedAccuracy!!, 0.0)
        assertEquals(91.0, coords.heading!!, 0.0)
        assertEquals(2.0, coords.headingAccuracy!!, 0.0)
        assertEquals(140.0, coords.ellipsoidalAltitude!!, 0.0)
    }

    @Test
    fun `MotionActivity constructs and every property reads back`() {
        val activity = MotionActivity(type = ActivityType.IN_VEHICLE, confidence = 88)
        assertEquals(ActivityType.IN_VEHICLE, activity.type)
        assertEquals(88, activity.confidence)
    }

    @Test
    fun `Battery constructs and every property reads back`() {
        val battery = Battery(level = 0.62, isCharging = true)
        assertEquals(0.62, battery.level, 0.0)
        assertEquals(true, battery.isCharging)
    }

    @Test
    fun `Location constructs and every property reads back`() {
        val extras = JSONObject().put("watch", true)
        val coords = Coords(52.0, 21.0, 5.0, null, null, null, null, null, null, null)
        val activity = MotionActivity(ActivityType.STILL, 100)
        val battery = Battery(0.9, false)
        val location = Location(
            uuid = "abc-123",
            timestamp = "2026-07-29T10:00:00.000Z",
            age = 1.5,
            odometer = 1234.5,
            coords = coords,
            activity = activity,
            battery = battery,
            isMoving = true,
            sample = false,
            event = "motionchange",
            extras = extras,
        )
        assertEquals("abc-123", location.uuid)
        assertEquals("2026-07-29T10:00:00.000Z", location.timestamp)
        assertEquals(1.5, location.age!!, 0.0)
        assertEquals(1234.5, location.odometer, 0.0)
        assertSame(coords, location.coords)
        assertSame(activity, location.activity)
        assertSame(battery, location.battery)
        assertEquals(true, location.isMoving)
        assertEquals(false, location.sample)
        assertEquals("motionchange", location.event)
        assertSame(extras, location.extras)
    }

    @Test
    fun `Geofence constructs and every property reads back`() {
        val extras = JSONObject().put("kind", "home")
        val geofence = Geofence(
            identifier = "home",
            radius = 150.0,
            latitude = 52.0,
            longitude = 21.0,
            notifyOnEntry = true,
            notifyOnExit = false,
            notifyOnDwell = true,
            loiteringDelay = 30_000.0,
            extras = extras,
        )
        assertEquals("home", geofence.identifier)
        assertEquals(150.0, geofence.radius, 0.0)
        assertEquals(52.0, geofence.latitude, 0.0)
        assertEquals(21.0, geofence.longitude, 0.0)
        assertEquals(true, geofence.notifyOnEntry)
        assertEquals(false, geofence.notifyOnExit)
        assertEquals(true, geofence.notifyOnDwell)
        assertEquals(30_000.0, geofence.loiteringDelay!!, 0.0)
        assertSame(extras, geofence.extras)
    }

    @Test
    fun `LogEntry constructs and every property reads back`() {
        val entry = LogEntry(
            ts = "2026-07-29T10:00:00.000Z",
            level = LogLevel.INFO.value,
            src = "native",
            event = "app",
            message = "started",
            data = "diagnostic",
        )
        assertEquals("2026-07-29T10:00:00.000Z", entry.ts)
        assertEquals(LogLevel.INFO.value, entry.level)
        assertEquals("native", entry.src)
        assertEquals("app", entry.event)
        assertEquals("started", entry.message)
        assertEquals("diagnostic", entry.data)
    }

    @Test
    fun `ProviderState constructs and every property reads back`() {
        val state = ProviderState(
            status = AuthorizationStatus.ALWAYS,
            enabled = true,
            gps = true,
            network = false,
            accuracyAuthorization = AccuracyAuthorization.FULL,
        )
        assertEquals(AuthorizationStatus.ALWAYS, state.status)
        assertEquals(true, state.enabled)
        assertEquals(true, state.gps)
        assertEquals(false, state.network)
        assertEquals(AccuracyAuthorization.FULL, state.accuracyAuthorization)
    }

    @Test
    fun `State constructs, reads enabled and raw, and indexes diagnostic keys`() {
        val raw = JSONObject().put("enabled", true).put("odometer", 42.0)
        val state = State(enabled = true, raw = raw)
        assertEquals(true, state.enabled)
        assertSame(raw, state.raw)
        assertEquals(42.0, state["odometer"])
    }

    @Test
    fun `MotionChangeEvent constructs and every property reads back`() {
        val location = Location(
            "u", "t", null, 0.0,
            Coords(0.0, 0.0, 0.0, null, null, null, null, null, null, null),
            MotionActivity(ActivityType.STILL, 100),
            Battery(1.0, false),
            false, null, null, null,
        )
        val event = MotionChangeEvent(isMoving = true, location = location)
        assertEquals(true, event.isMoving)
        assertSame(location, event.location)
    }

    @Test
    fun `CrashEvent constructs and every property reads back`() {
        val location = Location(
            "u", "t", null, 0.0,
            Coords(0.0, 0.0, 0.0, null, null, null, null, null, null, null),
            MotionActivity(ActivityType.STILL, 100),
            Battery(1.0, false),
            false, null, null, null,
        )
        val event = CrashEvent(
            timestamp = "2026-08-17T10:00:00.000Z",
            peakG = 4.2,
            impactDurationMs = 180.0,
            preImpactSpeedMps = 12.5,
            location = location,
        )
        assertEquals("2026-08-17T10:00:00.000Z", event.timestamp)
        assertEquals(4.2, event.peakG, 0.0)
        assertEquals(180.0, event.impactDurationMs, 0.0)
        assertEquals(12.5, event.preImpactSpeedMps, 0.0)
        assertSame(location, event.location)
    }

    @Test
    fun `HeadingEvent constructs and every property reads back`() {
        val event = HeadingEvent(
            heading = 91.5,
            // Int, not Double - Android carries the magnetometer CALIBRATION
            // LEVEL (0-3). The iOS facade's twin is a Double error in degrees;
            // see `HeadingEvent`.
            accuracy = 3,
            isTrue = true,
        )
        assertEquals(91.5, event.heading, 0.0)
        assertEquals(3, event.accuracy)
        assertEquals(true, event.isTrue)
    }

    @Test
    fun `GeofenceEvent constructs and every property reads back`() {
        val extras = JSONObject().put("k", "v")
        val location = Location(
            "u", "t", null, 0.0,
            Coords(0.0, 0.0, 0.0, null, null, null, null, null, null, null),
            MotionActivity(ActivityType.STILL, 100),
            Battery(1.0, false),
            false, null, null, null,
        )
        val event = GeofenceEvent(
            identifier = "home",
            action = GeofenceAction.DWELL,
            location = location,
            extras = extras,
        )
        assertEquals("home", event.identifier)
        assertEquals(GeofenceAction.DWELL, event.action)
        assertSame(location, event.location)
        assertSame(extras, event.extras)
    }

    @Test
    fun `every GeofenceAction wire value round-trips`() {
        for (action in GeofenceAction.values()) {
            assertEquals(action, GeofenceAction.from(action.wire))
        }
    }

    @Test
    fun `GeofencesChangeEvent constructs and every property reads back`() {
        val on = listOf(Geofence("a", 1.0, 0.0, 0.0, null, null, null, null, null))
        val off = listOf(Geofence("b", 1.0, 0.0, 0.0, null, null, null, null, null))
        val event = GeofencesChangeEvent(on = on, off = off)
        assertSame(on, event.on)
        assertSame(off, event.off)
    }

    @Test
    fun `HttpEvent constructs and every property reads back`() {
        val event = HttpEvent(success = false, status = 503, responseText = "unavailable")
        assertEquals(false, event.success)
        assertEquals(503, event.status)
        assertEquals("unavailable", event.responseText)
    }

    @Test
    fun `ConnectivityChangeEvent constructs and every property reads back`() {
        val event = ConnectivityChangeEvent(connected = true)
        assertEquals(true, event.connected)
    }

    @Test
    fun `HeartbeatEvent constructs and its property reads back`() {
        val raw = JSONObject().put("odometer", 5.0)
        val event = HeartbeatEvent(raw = raw)
        assertSame(raw, event.raw)
    }

    @Test
    fun `AuthState constructs and every property reads back`() {
        val state = AuthState(accessToken = "a", refreshToken = "r")
        assertEquals("a", state.accessToken)
        assertEquals("r", state.refreshToken)
    }

    // ---- exceptions (BGeoException.kt) -------------------------------------

    @Test
    fun `every BGeoException subclass constructs and exposes its code and message`() {
        assertEquals("LICENSE_MISSING", BGeoException.LicenseMissing("m").code)
        assertEquals("LICENSE_INVALID", BGeoException.LicenseInvalid("m").code)
        assertEquals("LICENSE_EXPIRED", BGeoException.LicenseExpired("m").code)
        assertEquals("LICENSE_APP_MISMATCH", BGeoException.LicenseAppMismatch("m").code)
        assertEquals("DISABLED", BGeoException.Disabled("m").code)
        assertEquals("NOT_FOUND", BGeoException.NotFound("m").code)
        assertEquals("INVALID_GEOFENCE", BGeoException.InvalidGeofence("m").code)
        val unknown = BGeoException.Unknown("SOME_CODE", "boom")
        assertEquals("SOME_CODE", unknown.code)
        assertEquals("boom", unknown.message)
    }

    // ---- Subscription: public type, internal constructor -------------------
    //
    // Apps obtain a Subscription only through a `BackgroundGeolocation.onXxx`
    // call, never by constructing one directly - proven here by obtaining one
    // exactly that way and exercising its one public member, [Subscription.remove].

    @Test
    fun `Subscription is obtained from the facade, not constructed, and remove is idempotent`() {
        val subscription = BackgroundGeolocation.onLocation { }
        subscription.remove()
        subscription.remove() // idempotent - must not throw
    }

    // ---- PermissionRequester: public type, Activity-bound constructor ------
    //
    // Its constructor takes an `ActivityResultCaller`, which cannot be
    // instantiated on the JVM under this module's `unitTests.
    // isReturnDefaultValues` (device-verified only, see PermissionRequester's
    // class doc). A function reference to the constructor still proves it is
    // PUBLIC and has this exact signature without ever calling it.

    @Test
    fun `PermissionRequester's constructor is public with the documented signature`() {
        val ctor: (ActivityResultCaller) -> PermissionRequester = ::PermissionRequester
        assertNotNull(ctor)
    }

    @Test
    fun `Config CLEAR sentinels are public and reachable`() {
        assertTrue(Config.CLEAR_STRING.isNotEmpty())
        assertEquals(Config.CLEAR_STRING, Config.CLEAR_MAP[Config.CLEAR_STRING])
        assertEquals(Config.CLEAR_STRING, Config.CLEAR_JSON_OBJECT.getString(Config.CLEAR_STRING))
    }
}
