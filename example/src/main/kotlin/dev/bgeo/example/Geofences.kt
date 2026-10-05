package dev.bgeo.example

import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.Geofence
import com.bgeo.sdk.addGeofence
import com.bgeo.sdk.getGeofences
import com.bgeo.sdk.removeGeofence

/**
 * Keep the app store in sync with the SDK's geofence set (the device is the
 * source of truth). Call after every CRUD operation.
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
    /**
     * Test seams: `BackgroundGeolocation` is a Kotlin `object` with static
     * members, so it can't be swapped for a fake directly. Each seam lets a
     * test inject a failure for any of the two CRUD paths and assert the
     * `AppStore` update is skipped.
     */
    private val addGeofenceCall: suspend (Geofence) -> Unit = { geofence -> BackgroundGeolocation.addGeofence(geofence) },
    private val removeGeofenceCall: suspend (String) -> Unit = { identifier -> BackgroundGeolocation.removeGeofence(identifier) },
    private val getGeofencesCall: suspend () -> List<Geofence> = { BackgroundGeolocation.getGeofences() },
) {

    /**
     * `geofences.ts`'s `syncGeofences`: read the SDK's current set and
     * update the store.
     */
    suspend fun refresh() {
        store.setGeofences(getGeofencesCall())
    }

    /**
     * Add (or, for an existing identifier, upsert) a geofence, then sync. A
     * failed engine call rethrows without ever calling [refresh] — `AppStore`
     * must not learn about a fence the device doesn't actually have.
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
}
