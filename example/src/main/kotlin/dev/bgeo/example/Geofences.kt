package dev.bgeo.example

import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.Geofence
import com.bgeo.sdk.addGeofence
import com.bgeo.sdk.getGeofences
import com.bgeo.sdk.removeGeofence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keep the app store and the web console in sync with the SDK's geofence set
 * (the device is the source of truth). Call after every CRUD operation.
 *
 * A Kotlin port of `react-native/example/src/geofences.ts` (14 lines — only
 * `syncGeofences`, this file's [refresh]); `ios/Example/Sources/Geofences.swift`
 * is the same port for iOS. `add`/`remove` are inlined in RN's
 * `GeofenceFormScreen.tsx`'s `onSave`/`onDelete` (SDK call, then
 * `await syncGeofences()`, wrapped in try/catch so a failed SDK call never
 * reaches the sync step); pulled out here as real methods, same as iOS, so
 * `GeofenceFormScreen.kt` stays thin.
 *
 * No `removeAll`: neither RN nor iOS's reference app wires a "clear all"
 * action anywhere (iOS's brief named it explicitly and added one anyway; this
 * task's brief explicitly says not to — see this file's task brief). The SDK
 * exposes `removeGeofences()` but there is no call site for it here.
 */
class Geofences(
    private val store: AppStore,
    private val deviceLink: DeviceLink,
    /**
     * Test seams: `BackgroundGeolocation` is a Kotlin `object` with static
     * members (see `DeviceLink.applyConfig`'s doc comment for the same
     * reasoning), so it can't be swapped for a fake directly. Each seam
     * lets a test inject a failure for any of the two CRUD paths and assert
     * the snapshot push (and the `AppStore` update) is skipped.
     */
    private val addGeofenceCall: suspend (Geofence) -> Unit = { geofence -> BackgroundGeolocation.addGeofence(geofence) },
    private val removeGeofenceCall: suspend (String) -> Unit = { identifier -> BackgroundGeolocation.removeGeofence(identifier) },
    private val getGeofencesCall: suspend () -> List<Geofence> = { BackgroundGeolocation.getGeofences() },
) {

    /**
     * `geofences.ts`'s `syncGeofences`: read the SDK's current set, update
     * the store, mirror the snapshot to the console. The PUT is a no-op when
     * not linked (or on any network/auth failure) — its result is
     * intentionally discarded, same as the RN original's fire-and-forget
     * `await`.
     */
    suspend fun refresh() {
        val geofences = getGeofencesCall()
        store.setGeofences(geofences)
        putGeofences(geofences)
    }

    /**
     * Add (or, for an existing identifier, upsert) a geofence, then sync. A
     * failed engine call rethrows without ever calling [refresh] — the
     * console must not learn about a fence the device doesn't actually have,
     * and `AppStore`/the server snapshot must not change either.
     */
    suspend fun add(geofence: Geofence) {
        addGeofenceCall(geofence)
        refresh()
    }

    /** Remove one geofence, then sync. Same failure guard as [add]. */
    suspend fun remove(identifier: String) {
        removeGeofenceCall(identifier)
        refresh()
    }

    /**
     * Mirrors the SDK's geofence set to the console via `PUT
     * {base}/device/geofences`. `DeviceLink.authorizedFetch` throws
     * `DeviceLinkError("not linked")` rather than returning null the way RN's
     * `deviceFetch`/iOS's `deviceFetch` do — caught here (along with any
     * other request failure) so this stays the same fire-and-forget no-op
     * both references describe, instead of surfacing "not linked" as a save
     * error to the user.
     */
    private suspend fun putGeofences(geofences: List<Geofence>) {
        val body = JSONObject().put("geofences", JSONArray().apply { geofences.forEach { put(it.toJson()) } })
        try {
            deviceLink.authorizedFetch("/device/geofences", method = "PUT", body = body.toString())
        } catch (e: Exception) {
            // Not linked, or the request failed — no-op, matching RN/iOS.
        }
    }
}
