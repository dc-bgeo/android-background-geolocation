package com.bgeo.sdk

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the Kotlin [Config] against the cross-SDK source of truth,
 * react-native/src/types.ts. Adding a key anywhere must fail here until this
 * facade agrees.
 */
class ConfigDriftTest {

    private fun keysDeclaredInTypesTs(): Set<String> {
        // Gradle runs unit tests with the module dir as the working directory.
        // The source of truth is a SIBLING checkout (monorepo layout on dev
        // machines). The standalone public-repo CI has no react-native next to
        // it — there the guard has nothing to compare against and must skip,
        // not fail (v0.1.4 tag CI 2026-08-17).
        val file = File("../../react-native/src/types.ts").canonicalFile
        assumeTrue("types.ts not found at ${file.path} — skipping drift guard", file.exists())
        val source = file.readText()

        val start = source.indexOf("export interface Config {")
        assertTrue("Config interface not found", start >= 0)
        val body = source.substring(start).substringBefore("\n}")

        // Property lines look like `  someKey?: type;` at two-space indent.
        return Regex("""^ {2}([A-Za-z][A-Za-z0-9_]*)\??:""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Every one of the 57 [Config] properties set to a concrete value.
     * Enumerated explicitly, in `types.ts` declaration order — a fixture
     * that only set 50 keys would let the other 7 vanish from this test
     * without failing it.
     */
    private fun everyKeyPopulated(): Config = Config(
        locationAuthorizationRequest = "Always",
        locationAuthorizationAlert = mapOf("titleWhenNotEnabled" to "Location required"),
        disableLocationAuthorizationAlert = true,
        backgroundPermissionRationale = mapOf("title" to "Rationale"),
        desiredAccuracy = DesiredAccuracy.HIGH.value,
        distanceFilter = 30.0,
        disableLocationFilter = false,
        locationFilterMaxAccuracy = 100.0,
        locationFilterMaxSpeed = 60.0,
        locationFilterPolicy = "Conservative",
        kalmanProfile = "DEFAULT",
        odometerAccuracyThreshold = 0.0,
        disableElasticity = false,
        elasticityMultiplier = 1.0,
        stationaryDesiredAccuracy = "BALANCED",
        stationaryLocationUpdateInterval = 30000,
        triggerActivities = "in_vehicle,on_bicycle,walking,running,on_foot",
        minimumActivityRecognitionConfidence = 75,
        activityRecognitionInterval = 10000,
        disableMotionActivityUpdates = false,
        stopTimeout = 5,
        showsBackgroundLocationIndicator = true,
        stationaryRadius = 25.0,
        stationaryDistanceFilter = 25.0,
        preventSuspend = false,
        heartbeatInterval = 60,
        motionTriggerDelay = 10000,
        locationUpdateInterval = 1000,
        foregroundService = false,
        notification = NotificationConfig(title = "Tracking"),
        stopOnTerminate = false,
        startOnBoot = true,
        debug = false,
        logLevel = LogLevel.INFO.value,
        logMaxDays = 3,
        logUrl = "https://example.test/log",
        maxDaysToPersist = 7,
        url = "https://example.test/locations",
        method = "POST",
        headers = mapOf("X-Test" to "1"),
        params = JSONObject().put("device", "test"),
        extras = JSONObject().put("source", "test"),
        httpRootProperty = "location",
        autoSync = true,
        disableAutoSyncOnCellular = false,
        autoSyncThreshold = 5,
        batchSync = false,
        maxBatchSize = 50,
        httpTimeoutMs = 60000,
        maxRecordsToPersist = 10000,
        authorization = AuthorizationConfig(
            strategy = "JWT",
            accessToken = "a",
            refreshToken = "r",
            refreshUrl = "https://example.test/refresh",
        ),
        stationaryKeepAlive = true,
        diagnosticExtras = false,
        useSessionEngine = true,
        geofenceProximityRadius = 1000.0,
        maxMonitoredGeofences = -1,
        geofenceInitialTriggerEntry = true,
        crashDetection = CrashDetectionConfig(enabled = true, minSpeed = 11.11, impactThreshold = 4.0),
        distractionDetection = DistractionDetectionConfig(enabled = true, minSpeed = 5.0, minEpisodeSec = 5.0),
    )

    @Test
    fun `Config covers exactly the keys types_ts declares`() {
        val expected = keysDeclaredInTypesTs()
        assertEquals("types.ts key count changed — update this expectation deliberately",
            59, expected.size)

        val json = everyKeyPopulated().toJson()
        val actual = json.keys().asSequence().toSet()

        assertTrue("Config is missing keys declared in types.ts: ${(expected - actual).sorted()}",
            (expected - actual).isEmpty())
        assertTrue("Config emits keys types.ts does not declare: ${(actual - expected).sorted()}",
            (actual - expected).isEmpty())
    }
}
