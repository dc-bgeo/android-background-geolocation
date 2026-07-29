package com.bgeo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the iOS plan's `BGeoErrorTests` case-for-case (see
 * ios/Tests/BackgroundGeolocationTests/BGeoErrorTests.swift): the engine's
 * `BGGeoCallback.error(code, message)` reject codes must map to typed
 * exceptions without ever swallowing an unrecognised code, and the numeric/
 * string constants must match `react-native/src/constants.ts` exactly since
 * apps compare `providerChange.status` against them directly.
 */
class BGeoExceptionTest {

    @Test
    fun `license codes map to their typed subclasses`() {
        assertTrue(BGeoException.from("LICENSE_MISSING", "m") is BGeoException.LicenseMissing)
        assertTrue(BGeoException.from("LICENSE_INVALID", "m") is BGeoException.LicenseInvalid)
        assertTrue(BGeoException.from("LICENSE_EXPIRED", "m") is BGeoException.LicenseExpired)
        assertTrue(BGeoException.from("LICENSE_APP_MISMATCH", "m") is BGeoException.LicenseAppMismatch)
    }

    @Test
    fun `known operational codes map to their typed subclasses`() {
        assertTrue(BGeoException.from("DISABLED", "m") is BGeoException.Disabled)
        assertTrue(BGeoException.from("NOT_FOUND", "m") is BGeoException.NotFound)
        assertTrue(BGeoException.from("INVALID_GEOFENCE", "m") is BGeoException.InvalidGeofence)
    }

    @Test
    fun `unknown code survives as Unknown with its code intact`() {
        val error = BGeoException.from("SOME_NEW_ENGINE_CODE", "boom")
        assertTrue(error is BGeoException.Unknown)
        assertEquals("SOME_NEW_ENGINE_CODE", error.code)
        assertEquals("boom", error.message)
    }

    @Test
    fun `code round-trips for every typed case`() {
        val codes = listOf(
            "LICENSE_MISSING", "LICENSE_INVALID", "LICENSE_EXPIRED", "LICENSE_APP_MISMATCH",
            "DISABLED", "NOT_FOUND", "INVALID_GEOFENCE", "WHATEVER",
        )
        for (code in codes) {
            assertEquals(code, BGeoException.from(code, "m").code)
        }
    }

    @Test
    fun `constants match the cross-SDK contract`() {
        assertEquals(3, AuthorizationStatus.ALWAYS.value)
        assertEquals(-1, DesiredAccuracy.HIGH.value)
        assertEquals("in_vehicle", ActivityType.IN_VEHICLE.wire)
        assertEquals(AuthorizationStatus.ALWAYS, AuthorizationStatus.from(3))
        assertEquals(ActivityType.UNKNOWN, ActivityType.from("teleporting"))
    }

    @Test
    fun `every DesiredAccuracy value matches the contract`() {
        assertEquals(-2, DesiredAccuracy.NAVIGATION.value)
        assertEquals(-1, DesiredAccuracy.HIGH.value)
        assertEquals(10, DesiredAccuracy.MEDIUM.value)
        assertEquals(100, DesiredAccuracy.LOW.value)
        assertEquals(1000, DesiredAccuracy.VERY_LOW.value)
        assertEquals(3000, DesiredAccuracy.LOWEST.value)
    }

    @Test
    fun `every LogLevel value matches the contract`() {
        assertEquals(0, LogLevel.OFF.value)
        assertEquals(1, LogLevel.ERROR.value)
        assertEquals(2, LogLevel.WARNING.value)
        assertEquals(3, LogLevel.INFO.value)
        assertEquals(4, LogLevel.DEBUG.value)
        assertEquals(5, LogLevel.VERBOSE.value)
    }

    @Test
    fun `every AuthorizationStatus value matches the contract`() {
        assertEquals(0, AuthorizationStatus.NOT_DETERMINED.value)
        assertEquals(1, AuthorizationStatus.RESTRICTED.value)
        assertEquals(2, AuthorizationStatus.DENIED.value)
        assertEquals(3, AuthorizationStatus.ALWAYS.value)
        assertEquals(4, AuthorizationStatus.WHEN_IN_USE.value)
    }

    @Test
    fun `every AccuracyAuthorization value matches the contract`() {
        assertEquals(0, AccuracyAuthorization.FULL.value)
        assertEquals(1, AccuracyAuthorization.REDUCED.value)
    }

    @Test
    fun `every ActivityType wire value matches the contract`() {
        assertEquals("still", ActivityType.STILL.wire)
        assertEquals("on_foot", ActivityType.ON_FOOT.wire)
        assertEquals("walking", ActivityType.WALKING.wire)
        assertEquals("running", ActivityType.RUNNING.wire)
        assertEquals("on_bicycle", ActivityType.ON_BICYCLE.wire)
        assertEquals("in_vehicle", ActivityType.IN_VEHICLE.wire)
        assertEquals("unknown", ActivityType.UNKNOWN.wire)
    }

    @Test
    fun `AuthorizationStatus from round-trips every valid value`() {
        for (status in AuthorizationStatus.values()) {
            assertEquals(status, AuthorizationStatus.from(status.value))
        }
    }

    @Test
    fun `ActivityType from round-trips every valid wire value`() {
        for (type in ActivityType.values()) {
            assertEquals(type, ActivityType.from(type.wire))
        }
    }

    @Test
    fun `ActivityType from falls back to UNKNOWN for unrecognised or null wire values`() {
        assertEquals(ActivityType.UNKNOWN, ActivityType.from("teleporting"))
        assertEquals(ActivityType.UNKNOWN, ActivityType.from(null))
    }
}
