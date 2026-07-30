package dev.bgeo.example

import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

/**
 * Single log pipeline: [logEvent] writes the structured line into the app
 * store (Logs screen) AND into the SDK's own persisted log queue via
 * `BackgroundGeolocation.logger`, which survives app kills and uploads
 * batches to `/device/logs` with the engine's own auth — unlike an app-only
 * buffer that dies with the process.
 *
 * A Kotlin port of `react-native/example/src/logUploader.ts`;
 * `ios/Example/Sources/LogUploader.swift` is the same port for iOS. This is
 * meant to become the app's ONLY logging entry point once a later task
 * rewires every screen's own inline `appStore.appendLog` call through here
 * (`MapScreen.kt`/`SettingsScreen.kt` still log directly) — not done in this
 * task; see the task report.
 *
 * **`Logger.write` hard-codes `event: "app"`** (`sdk/.../Logger.kt`: every
 * write goes through `engine.log(level, "app", message, data, tag, "native")`
 * — the real event name never reaches the engine's `event` column). This
 * does not need a workaround here: the real event name still travels inside
 * the `data` payload's own `"event"` key (built below), which is all
 * `LogsScreen.kt`'s native-vs-app-authored split needs — it filters a
 * `getLog()` poll down to `event != "app"`, the same way iOS's
 * `LogUploader.swift`/`LogsScreen.swift` document doing for the identical
 * constraint on that platform.
 *
 * **Redaction, built in from the start — not left to future call sites.**
 * [redact] strips any [SENSITIVE_KEYS] string value out of [data] before it
 * reaches EITHER `store.appendLog` or the SDK's persisted log queue,
 * recursively, so a nested object/array can't smuggle a credential past it.
 * A later task will wire `BackgroundGeolocation.onAuthorization`'s raw
 * `{success, accessToken, refreshToken}` event body straight into this
 * function's `data` parameter; on both the iOS and RN consoles that body
 * reached the on-device log, the `/device/logs` upload, and the Logs
 * screen's UI verbatim before it was caught and patched at the call site.
 * Redacting here instead means no future call site has to remember to do it
 * for `data` — see `LogUploaderTest` for the test that proves the raw token
 * strings never appear in either sink.
 *
 * **What this does NOT cover on its own: free-text `message`/`event`.**
 * Neither reference client redacts inside `logEvent` at all — iOS's fix is
 * call-site-specific, guarding only `onAuthorization`'s payload before it's
 * ever passed in. This function protects every call site by construction
 * instead, but only for values it can identify by *key* — a plain `String`
 * has no key. So [logEvent] also scrubs, verbatim, every literal value
 * [redact]/[redactArray] found and replaced in [data] out of [message] and
 * [event] too (`secrets` below): a call site that echoes the same token into
 * a human-readable message (e.g. `"refreshed: $accessToken"` where the same
 * `accessToken` is also present in `data`) doesn't leak it that way either.
 * What it still can't catch: a secret that appears ONLY in `message`/`event`
 * and never in `data` — there's no key there to match against. Call sites
 * should keep secrets out of free text entirely, the same convention the
 * reference clients follow (`"refreshed"`, never the token itself).
 */
class LogUploader(
    private val store: AppStore,
    /**
     * Test seam: `BackgroundGeolocation.logger` is backed by a Kotlin
     * `object` with static members (same reasoning as `DeviceLink`'s
     * `applyConfig` and `Geofences`'s `*Call` properties), so it can't be
     * swapped for a fake directly — tests inject a plain lambda instead and
     * assert on exactly the `(level, message, payload)` triple it receives.
     */
    private val write: (LogLevel, String, JSONObject?) -> Unit = { level, message, data ->
        when (level) {
            LogLevel.ERROR -> BackgroundGeolocation.logger.error(message, data)
            LogLevel.WARN -> BackgroundGeolocation.logger.warn(message, data)
            LogLevel.INFO -> BackgroundGeolocation.logger.info(message, data)
            LogLevel.DEBUG -> BackgroundGeolocation.logger.debug(message, data)
            LogLevel.VERBOSE -> BackgroundGeolocation.logger.verbose(message, data)
        }
    },
) {
    /**
     * `logUploader.ts`'s `logEvent`: append the structured line to the app
     * store's log buffer (Logs screen), then hand the same event to the
     * SDK's persisted/uploaded log queue with the shape both reference
     * clients send — `write(message ?? event, {event, ...(data != null ?
     * {data} : {})})`.
     *
     * `level` stays required, with no default: both reference clients pass
     * it explicitly at every call site, and a later task's per-event-type
     * severity (an error subscription logging at `ERROR`, a location update
     * at `INFO`, ...) is exactly the case a defaulted level would silently
     * paper over — the same class of gap iOS's review found for the missing
     * `data` parameter.
     */
    fun logEvent(event: String, level: LogLevel, message: String? = null, data: JSONObject? = null) {
        val secrets = mutableListOf<String>()
        val redacted = data?.let { redact(it, secrets) }
        val safeEvent = scrub(event, secrets)
        val safeMessage = message?.let { scrub(it, secrets) }
        store.appendLog(LogLine(ts = isoTimestamp(), level = level, event = safeEvent, message = safeMessage, data = redacted))
        val payload = JSONObject().put("event", safeEvent)
        redacted?.let { payload.put("data", it) }
        write(level, safeMessage ?: safeEvent, payload)
    }
}

/**
 * Removes every literal value [redact]/[redactArray] found and replaced in
 * `data` (collected into `secrets` as they're found) from an unrelated free
 * string too — see [logEvent]'s header note on why `message`/`event` need
 * this on top of the key-based redaction, which only applies to values it
 * can reach through a key.
 */
private fun scrub(value: String, secrets: List<String>): String =
    secrets.fold(value) { acc, secret -> if (secret.isEmpty()) acc else acc.replace(secret, "<redacted>") }

/**
 * Keys whose string value is a credential, never a log-safe value, matched
 * on the FULL key name (case-insensitive, `_` stripped) so `accessToken`,
 * `access_token` and `ACCESS_TOKEN` are all one entry — camelCase (the shape
 * `BackgroundGeolocation.onAuthorization`/`onProviderChange` emit),
 * snake_case (the server API and `StoredLink`'s own persisted JSON) and the
 * bare/HTTP-header spellings a later task's `onHttp`/headers-map
 * subscriptions can carry (`token`, `Authorization`, `bearer`, `id_token`/
 * `idToken`, `jwt`).
 *
 * **Whole-key match, deliberately not substring/prefix/suffix.** A future
 * subscription's diagnostic fields can legitimately contain "token" or
 * "authorization" as part of a longer, unrelated name —
 * `onProviderChange`'s real `accuracyAuthorization` field is exactly this,
 * and a hypothetical `tokenCount` would be another. Matching on
 * `key.contains("token")` would redact both into uselessness; the whole-key
 * match after normalizing case/`_` only fires when the key IS one of these
 * names, not merely contains one. See `LogUploaderTest` for both directions:
 * every spelling here redacted, `accuracyAuthorization`/`tokenCount`
 * preserved verbatim.
 */
private val SENSITIVE_KEYS = setOf(
    "accesstoken", "refreshtoken", "idtoken", "token", "authorization", "bearer", "jwt",
)

private fun isSensitiveKey(key: String): Boolean = key.lowercase(Locale.US).replace("_", "") in SENSITIVE_KEYS

/**
 * Deep copy of [json] with every [SENSITIVE_KEYS] string value replaced by a
 * fixed marker (never simply dropped — a missing key would erase the "there
 * was a token here" signal the redacted line is supposed to preserve, e.g.
 * `DeviceLink.kt`'s `StoredLink.toString()` keeps the same
 * `accessToken=<redacted>` shape). Nested objects/arrays are walked
 * recursively so a token can't hide one level down. Every raw value replaced
 * this way is appended to [secrets] so [LogUploader.logEvent] can also strip
 * it out of the free-text `message`/`event` it writes alongside `data`.
 */
private fun redact(json: JSONObject, secrets: MutableList<String>): JSONObject {
    val result = JSONObject()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = json.opt(key)
        result.put(
            key,
            when {
                value is String && isSensitiveKey(key) -> {
                    secrets += value
                    "<redacted>"
                }
                value is JSONObject -> redact(value, secrets)
                value is JSONArray -> redactArray(value, secrets)
                else -> value
            },
        )
    }
    return result
}

private fun redactArray(array: JSONArray, secrets: MutableList<String>): JSONArray {
    val result = JSONArray()
    for (i in 0 until array.length()) {
        when (val value = array.opt(i)) {
            is JSONObject -> result.put(redact(value, secrets))
            is JSONArray -> result.put(redactArray(value, secrets))
            else -> result.put(value)
        }
    }
    return result
}

/**
 * `ISO8601DateFormatter`'s JVM equivalent, pinned to `Locale.US` + UTC —
 * same convention `MapScreen.kt`'s `isoNow()` and
 * `CoordinatesSheet.kt`'s `PointFormat.parseIso` already use, so a log
 * timestamp can't render with a device-locale-dependent decimal separator or
 * offset.
 */
private fun isoTimestamp(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}
