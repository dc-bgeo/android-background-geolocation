package com.bgeo.sdk

import android.content.Context
import com.bgeo.BGGeoCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test double for [Engine]. Records every call it receives and lets tests
 * drive [eventEmitter] directly via [emit]. Used by the [EventHub] tests in
 * this task, and by the facade tests in later tasks.
 *
 * [Engine.init] takes an Android [Context]; on a pure-JVM unit test there is
 * no real one to construct, so this fake never touches it — it just records
 * that the call happened. Facade tests must never pass a real `Context` here.
 */
internal class FakeEngine : Engine {

    /** Outcome for a [BGGeoCallback]-shaped stub: either arg resolves success or error. */
    sealed class Outcome {
        data class Success(val result: JSONObject?) : Outcome()
        data class Failure(val code: String, val message: String) : Outcome()

        companion object {
            fun success(result: JSONObject? = null): Outcome = Success(result)
            fun failure(code: String, message: String): Outcome = Failure(code, message)
        }
    }

    private fun resolve(callback: BGGeoCallback, outcome: Outcome) {
        when (outcome) {
            is Outcome.Success -> callback.success(outcome.result)
            is Outcome.Failure -> callback.error(outcome.code, outcome.message)
        }
    }

    // ---- eventEmitter ---------------------------------------------------

    override var eventEmitter: ((String, JSONObject) -> Unit)? = null

    /** Calls whatever [EventHub.attach] installed, exactly as the real engine would. */
    fun emit(name: String, body: JSONObject) {
        eventEmitter?.invoke(name, body)
    }

    // ---- lifecycle / config / state --------------------------------------

    var initCallCount = 0
    override fun init(context: Context) {
        initCallCount++
    }

    var resumeTrackingIfEnabledCallCount = 0
    override fun resumeTrackingIfEnabled() {
        resumeTrackingIfEnabledCallCount++
    }

    var stubbedLicenseErrorCode: String? = null
    override fun licenseErrorCode(): String? = stubbedLicenseErrorCode

    val appliedConfigs = mutableListOf<JSONObject?>()
    override fun applyConfig(map: JSONObject?) {
        appliedConfigs.add(map)
    }

    var stubbedStateMap: JSONObject = JSONObject()
    override fun stateMap(): JSONObject = stubbedStateMap

    var stubbedIsEnabled = false
    override fun isEnabled(): Boolean = stubbedIsEnabled

    var startTrackingCallCount = 0
    override fun startTracking() {
        startTrackingCallCount++
    }

    var stopTrackingCallCount = 0
    override fun stopTracking() {
        stopTrackingCallCount++
    }

    var stubbedChangePaceResult = true
    val changePaceCalls = mutableListOf<Boolean>()
    override fun changePace(isMoving: Boolean): Boolean {
        changePaceCalls.add(isMoving)
        return stubbedChangePaceResult
    }

    var stubbedOdometer: Double = 0.0
    override fun currentOdometer(): Double = stubbedOdometer

    var stubbedSetOdometer: Outcome = Outcome.success()
    val setOdometerValues = mutableListOf<Double>()
    override fun setOdometer(value: Double, callback: BGGeoCallback) {
        setOdometerValues.add(value)
        resolve(callback, stubbedSetOdometer)
    }

    // ---- single-shot / watch ---------------------------------------------

    var stubbedCurrentPosition: Outcome = Outcome.success()
    val getCurrentPositionOptions = mutableListOf<JSONObject?>()
    override fun getCurrentPosition(options: JSONObject?, callback: BGGeoCallback) {
        getCurrentPositionOptions.add(options)
        resolve(callback, stubbedCurrentPosition)
    }

    val startWatchOptions = mutableListOf<JSONObject?>()
    override fun startWatch(options: JSONObject?) {
        startWatchOptions.add(options)
    }

    var stopWatchCallCount = 0
    override fun stopWatch() {
        stopWatchCallCount++
    }

    // ---- provider / permission state -------------------------------------

    var stubbedHasFineOrCoarse = false
    override fun hasFineOrCoarse(): Boolean = stubbedHasFineOrCoarse

    var stubbedHasBackground = false
    override fun hasBackground(): Boolean = stubbedHasBackground

    var stubbedHasActivityRecognition = false
    override fun hasActivityRecognition(): Boolean = stubbedHasActivityRecognition

    var stubbedWantsAlways = true
    override fun wantsAlways(): Boolean = stubbedWantsAlways

    var stubbedNumericStatus = 0
    override fun numericStatus(): Int = stubbedNumericStatus

    var stubbedProviderState: JSONObject = JSONObject()
    override fun providerState(): JSONObject = stubbedProviderState

    var emitProviderChangeCallCount = 0
    override fun emitProviderChange() {
        emitProviderChangeCallCount++
    }

    var stubbedLocationServicesEnabled = true
    override fun locationServicesEnabled(): Boolean = stubbedLocationServicesEnabled

    var stubbedIsPowerSaveMode = false
    override fun isPowerSaveMode(): Boolean = stubbedIsPowerSaveMode

    // ---- upload queue -----------------------------------------------------

    var syncCallCount = 0
    var stubbedSync: Outcome = Outcome.success()
    override fun sync(callback: BGGeoCallback) {
        syncCallCount++
        resolve(callback, stubbedSync)
    }

    var stubbedGetLocations: Outcome = Outcome.success()
    override fun getLocations(callback: BGGeoCallback) {
        resolve(callback, stubbedGetLocations)
    }

    var stubbedDestroyLocations: Outcome = Outcome.success()
    override fun destroyLocations(callback: BGGeoCallback) {
        resolve(callback, stubbedDestroyLocations)
    }

    var stubbedPendingCount = 0
    override fun pendingCount(): Int = stubbedPendingCount

    var stubbedDestroyLocation = true
    val destroyLocationUuids = mutableListOf<String>()
    override fun destroyLocation(uuid: String): Boolean {
        destroyLocationUuids.add(uuid)
        return stubbedDestroyLocation
    }

    val insertedLocations = mutableListOf<JSONObject>()
    var stubbedInsertLocation: Outcome = Outcome.success()
    override fun insertLocation(location: JSONObject, callback: BGGeoCallback) {
        insertedLocations.add(location)
        resolve(callback, stubbedInsertLocation)
    }

    var stubbedAuthStateMap: JSONObject = JSONObject()
    override fun authStateMap(): JSONObject = stubbedAuthStateMap

    // ---- logger -------------------------------------------------------------

    var stubbedNewestLogs: List<JSONObject> = emptyList()
    val newestLogsLimits = mutableListOf<Int>()
    override fun newestLogs(limit: Int): List<JSONObject> {
        newestLogsLimits.add(limit)
        return stubbedNewestLogs
    }

    var stubbedDeleteAllLogs = 0
    override fun deleteAllLogs(): Int = stubbedDeleteAllLogs

    var stubbedPendingLogCount = 0
    override fun pendingLogCount(): Int = stubbedPendingLogCount

    var flushLogsCallCount = 0
    override fun flushLogs() {
        flushLogsCallCount++
    }

    data class LogCall(
        val level: Int,
        val event: String,
        val message: String?,
        val data: String?,
        val tag: String,
        val src: String,
    )
    val logCalls = mutableListOf<LogCall>()
    override fun log(level: Int, event: String, message: String?, data: String?, tag: String, src: String) {
        logCalls.add(LogCall(level, event, message, data, tag, src))
    }

    val setLoggerForegroundCalls = mutableListOf<Boolean>()
    override fun setLoggerForeground(foreground: Boolean) {
        setLoggerForegroundCalls.add(foreground)
    }

    // ---- geofences ----------------------------------------------------------

    var stubbedAddGeofences: Outcome = Outcome.success()
    val addGeofencesCalls = mutableListOf<JSONArray?>()
    override fun addGeofences(geofences: JSONArray?, callback: BGGeoCallback) {
        addGeofencesCalls.add(geofences)
        resolve(callback, stubbedAddGeofences)
    }

    var stubbedRemoveGeofence: Outcome = Outcome.success()
    val removeGeofenceIdentifiers = mutableListOf<String>()
    override fun removeGeofence(identifier: String, callback: BGGeoCallback) {
        removeGeofenceIdentifiers.add(identifier)
        resolve(callback, stubbedRemoveGeofence)
    }

    var stubbedRemoveGeofences: Outcome = Outcome.success()
    var removeGeofencesCallCount = 0
    override fun removeGeofences(callback: BGGeoCallback) {
        removeGeofencesCallCount++
        resolve(callback, stubbedRemoveGeofences)
    }

    var stubbedGetGeofences: Outcome = Outcome.success()
    override fun getGeofences(callback: BGGeoCallback) {
        resolve(callback, stubbedGetGeofences)
    }

    var stubbedGeofenceExists: Outcome = Outcome.success()
    val geofenceExistsIdentifiers = mutableListOf<String>()
    override fun geofenceExists(identifier: String, callback: BGGeoCallback) {
        geofenceExistsIdentifiers.add(identifier)
        resolve(callback, stubbedGeofenceExists)
    }
}
