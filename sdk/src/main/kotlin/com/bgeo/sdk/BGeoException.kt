package com.bgeo.sdk

/**
 * Typed wrapper over the engine's `BGGeoCallback.error(code, message)` reject
 * pairs. An unrecognised code maps to [Unknown] rather than being swallowed,
 * so new engine codes remain diagnosable through [code] verbatim.
 */
sealed class BGeoException(val code: String, message: String) : Exception(message) {
    class LicenseMissing(message: String) : BGeoException("LICENSE_MISSING", message)
    class LicenseInvalid(message: String) : BGeoException("LICENSE_INVALID", message)
    class LicenseExpired(message: String) : BGeoException("LICENSE_EXPIRED", message)
    class LicenseAppMismatch(message: String) : BGeoException("LICENSE_APP_MISMATCH", message)
    class Disabled(message: String) : BGeoException("DISABLED", message)
    class NotFound(message: String) : BGeoException("NOT_FOUND", message)
    class InvalidGeofence(message: String) : BGeoException("INVALID_GEOFENCE", message)
    class Unknown(code: String, message: String) : BGeoException(code, message)

    companion object {
        fun from(code: String, message: String): BGeoException = when (code) {
            "LICENSE_MISSING" -> LicenseMissing(message)
            "LICENSE_INVALID" -> LicenseInvalid(message)
            "LICENSE_EXPIRED" -> LicenseExpired(message)
            "LICENSE_APP_MISMATCH" -> LicenseAppMismatch(message)
            "DISABLED" -> Disabled(message)
            "NOT_FOUND" -> NotFound(message)
            "INVALID_GEOFENCE" -> InvalidGeofence(message)
            else -> Unknown(code, message)
        }
    }
}
