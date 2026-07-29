package com.bgeo.sdk

import org.json.JSONObject

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

        /**
         * Decodes the `locationerror` event payload `{code, message}` into the
         * same typed [BGeoException] hierarchy [from] produces for a rejected
         * callback - so `onLocationError`/`locationErrors` and a thrown
         * `getCurrentPosition` rejection are indistinguishable to a caller
         * that only cares about the code/message.
         *
         * The engine emits both fields as JSON strings at every
         * `locationerror` site (`BGGeoEngine.kt:1122`'s license-gate check and
         * `:1160`'s watchPosition tick both build the payload from a Kotlin
         * `String`, per `BGGeoCallback.error(code: String, message: String)`'s
         * signature) - there is no NUMBER-coded shape to handle on Android,
         * unlike the iOS twin. [stringOrNull] is still used (rather than a
         * raw read) so a payload missing `code`/`message` entirely, or
         * carrying a JSON `null`/empty string for either, drops the event
         * instead of crashing - though note `stringOrNull` (unlike
         * `intOrNull`/`boolOrNull`) does not itself guard against a
         * genuinely mistyped value: `org.json`'s `optString` stringifies a
         * JSON number/object/array rather than rejecting it, so e.g. a
         * numeric `code` would still decode (as its string form), not drop.
         */
        internal fun fromLocationErrorEvent(json: JSONObject): BGeoException? {
            val code = json.stringOrNull("code") ?: return null
            val message = json.stringOrNull("message") ?: return null
            return from(code, message)
        }
    }
}
