package dev.bgeo.example

import android.content.SharedPreferences
import com.bgeo.sdk.AuthorizationConfig
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.Config
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * A Kotlin port of `react-native/example/src/deviceLink.ts` (the cross-client
 * contract); `ios/Example/Sources/DeviceLink.swift` is the same port for iOS
 * and agrees with every decision made here except where noted below.
 *
 * Links this install to a BGeo account using a registration code from the
 * web console, then points the SDK's native uploader at the demo server with
 * JWT auth (native refresh via refreshUrl survives killed-app uploads).
 */

/** Surfaces the server's own error message (`detail`, falling back to `error`, falling back to a generic message). */
class DeviceLinkError(message: String) : Exception(message)

/** Persisted device link: server, device id and JWT pair. The once-generated install uuid lives under its own storage key. */
data class StoredLink(
    val serverUrl: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
) {
    /** Redacted: this is `link()`'s return value, and Task 7's log uploader means it can end up in a log line. */
    override fun toString(): String =
        "StoredLink(serverUrl=$serverUrl, deviceId=$deviceId, accessToken=<redacted>, refreshToken=<redacted>)"
}

/**
 * Device metadata `DeviceLink` needs for registration. A plain data class
 * rather than reading `android.os.Build` directly, because the unit-test
 * harness stubs all of `android.jar` (`Build.MODEL` etc. return stub values
 * under `isReturnDefaultValues = true`) — production code builds this from
 * `Build.MODEL` / `Build.VERSION.RELEASE` / the app's `versionName` at the
 * call site, not inside this class.
 */
data class DeviceInfo(
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

/**
 * Narrow key-value storage `DeviceLink` persists through. `SharedPreferences`
 * itself is an `android.jar` interface and is stubbed in unit tests, so this
 * seam exists to let tests use a plain in-memory fake instead.
 */
interface Storage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStorage(private val prefs: SharedPreferences) : Storage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * `link`/`unlink` diverge from the brief's paraphrased signatures: both RN's
 * `linkDevice(serverUrl, code)` and iOS's `link(serverUrl:code:)` take the
 * server URL explicitly as a parameter, sourced from the caller's own UI
 * state (not read back out of `AppStore`, which only records it as a
 * side-effect of a successful link). Followed the references, not the brief.
 */
class DeviceLink(
    private val http: Http,
    private val storage: Storage,
    private val deviceInfo: DeviceInfo,
    private val store: AppStore,
    /**
     * Test seam: `BackgroundGeolocation` is a Kotlin `object` with static
     * members (and its own `engine` test seam is `internal` to the `:sdk`
     * module, invisible from here), so it cannot be swapped for a fake the
     * way the SDK's own `Engine` is. Injecting a suspend lambda — the same
     * approach `DeviceLink.swift` uses for the equivalent problem with
     * `BackgroundGeolocation` there — lets tests assert on exactly the
     * `Config` handed to `setConfig` without a second protocol/interface.
     */
    private val applyConfig: suspend (Config) -> Unit = { BackgroundGeolocation.setConfig(it) },
) {
    /** Serialises the 401-refresh section of [authorizedFetch] — see its doc comment. */
    private val refreshMutex = Mutex()

    private fun installUuid(): String {
        storage.getString(INSTALL_UUID_KEY)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        storage.putString(INSTALL_UUID_KEY, fresh)
        return fresh
    }

    private fun loadStoredLink(): StoredLink? {
        val raw = storage.getString(LINK_KEY) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            StoredLink(
                serverUrl = json.getString("serverUrl"),
                deviceId = json.getString("deviceId"),
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
            )
        }.getOrNull()
    }

    private fun saveStoredLink(link: StoredLink) {
        val json = JSONObject()
            .put("serverUrl", link.serverUrl)
            .put("deviceId", link.deviceId)
            .put("accessToken", link.accessToken)
            .put("refreshToken", link.refreshToken)
        storage.putString(LINK_KEY, json.toString())
    }

    private suspend fun applySdkConfig(link: StoredLink) {
        applyConfig(
            Config(
                url = "${link.serverUrl}/device/locations",
                logUrl = "${link.serverUrl}/device/logs",
                autoSync = true,
                batchSync = true,
                maxBatchSize = 50,
                authorization = AuthorizationConfig(
                    strategy = "JWT",
                    accessToken = link.accessToken,
                    refreshToken = link.refreshToken,
                    refreshUrl = "${link.serverUrl}/device/auth/refresh",
                    refreshPayload = JSONObject().put("refresh_token", "{refreshToken}"),
                ),
            ),
        )
    }

    /** Exchange a registration code for device tokens and configure the SDK. */
    suspend fun link(serverUrl: String, code: String): StoredLink {
        val uuid = installUuid()
        val body = JSONObject().apply {
            put("code", code.trim())
            put(
                "device",
                JSONObject().apply {
                    put("uuid", uuid)
                    put("model", deviceInfo.model)
                    put("platform", "android")
                    put("osVersion", deviceInfo.osVersion)
                    put("appVersion", deviceInfo.appVersion)
                    put("name", "BGeoExample (android)")
                },
            )
        }

        val response = http.send(
            HttpRequest(
                method = "POST",
                url = "$serverUrl/device/register",
                headers = mapOf("Content-Type" to "application/json"),
                body = body.toString(),
            ),
        )
        if (response.status !in 200..299) {
            throw DeviceLinkError(serverErrorMessage(response.body, response.status, "register"))
        }

        val link = try {
            val json = JSONObject(response.body)
            StoredLink(
                serverUrl = serverUrl,
                deviceId = json.getString("device_id"),
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
            )
        } catch (e: JSONException) {
            throw DeviceLinkError("register response malformed")
        }
        saveStoredLink(link)
        applySdkConfig(link)
        store.setLink(serverUrl = serverUrl, linked = true, deviceId = link.deviceId)
        return link
    }

    /** Clears persistence and unsets the SDK's `url`/`logUrl`/`authorization` via the clear sentinel (not empty strings — see `Config.CLEAR_STRING`). */
    suspend fun unlink() {
        storage.remove(LINK_KEY)
        applyConfig(Config(url = Config.CLEAR_STRING, logUrl = Config.CLEAR_STRING, authorization = AuthorizationConfig.CLEAR))
        store.setLink(linked = false, clearDeviceId = true)
    }

    /**
     * Authorized fetch against the linked demo server with one 401-refresh
     * retry. A second 401 (after a successful refresh) is returned as-is
     * rather than retried again — a dead refresh token must not be retried
     * indefinitely. A refresh that itself fails throws, surfacing the
     * refresh's own error rather than the original 401.
     *
     * The refresh itself is guarded by [refreshMutex]: two calls racing to a
     * 401 at the same time (e.g. geofence sync and history load firing
     * together on screen entry) must not both POST `/device/auth/refresh` —
     * with single-use refresh tokens the loser would surface a spurious
     * "refresh token revoked" instead of quietly reusing the winner's fresh
     * pair. Whoever gets the lock second re-checks storage first: if the
     * other caller already refreshed (the stored access token moved past the
     * one that triggered *this* 401), reuse it instead of refreshing again.
     */
    suspend fun authorizedFetch(path: String, method: String = "GET", body: String? = null): HttpResponse {
        var link = loadStoredLink() ?: throw DeviceLinkError("not linked")
        val url = "${link.serverUrl}$path"
        fun request(token: String) = HttpRequest(
            method = method,
            url = url,
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            body = body,
        )

        var response = http.send(request(link.accessToken))
        if (response.status == 401) {
            val staleAccessToken = link.accessToken
            link = refreshMutex.withLock {
                val current = loadStoredLink() ?: link
                if (current.accessToken != staleAccessToken) current else refreshTokens(current)
            }
            response = http.send(request(link.accessToken))
        }
        return response
    }

    private suspend fun refreshTokens(link: StoredLink): StoredLink {
        val response = http.send(
            HttpRequest(
                method = "POST",
                url = "${link.serverUrl}/device/auth/refresh",
                headers = mapOf("Content-Type" to "application/json"),
                body = JSONObject().put("refresh_token", link.refreshToken).toString(),
            ),
        )
        if (response.status !in 200..299) {
            throw DeviceLinkError(serverErrorMessage(response.body, response.status, "refresh"))
        }
        val refreshed = try {
            val json = JSONObject(response.body)
            link.copy(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
            )
        } catch (e: JSONException) {
            throw DeviceLinkError("refresh response malformed")
        }
        saveStoredLink(refreshed)
        applySdkConfig(refreshed)
        return refreshed
    }

    companion object {
        private const val LINK_KEY = "bgeo:link"
        private const val INSTALL_UUID_KEY = "bgeo:installUuid"
    }
}

/**
 * The body's `detail` field, falling back to `error`, falling back to a
 * generic "`action` failed (`status`)" message — matches `deviceLink.ts`'s
 * `body.detail ?? body.error ?? ...` and `DeviceLink.swift`'s equivalent.
 * Deliberately not `JSONObject.optString`: a present-but-wrong-typed value
 * (or a JSON `null`) must not silently coerce to `""` and mask the real
 * fallback chain (see `JsonDecoding.kt` in `:sdk` for the same reasoning —
 * its helpers are `internal` to that module and not visible here).
 */
private fun serverErrorMessage(body: String, status: Int, action: String): String {
    val json = runCatching { JSONObject(body) }.getOrNull()
    return json?.stringOrNull("detail")
        ?: json?.stringOrNull("error")
        ?: "$action failed ($status)"
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) (opt(key) as? String) else null
