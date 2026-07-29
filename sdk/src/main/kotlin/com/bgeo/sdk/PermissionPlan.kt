package com.bgeo.sdk

import android.Manifest

/**
 * Pure, Android-free decision: given the current grants, what permission set
 * to request next. Ported from the escalation order in
 * `react-native/android/src/main/java/com/bgeo/rn/BackgroundGeolocationModule.kt:282-320`.
 *
 * Kept free of any Activity/Context so the escalation ORDER is unit-testable
 * on the JVM; the Activity-bound plumbing that drives this is
 * [PermissionRequester], which is device-verified only.
 */
internal object PermissionPlan {
    data class Grants(
        val hasFineOrCoarse: Boolean,
        val hasBackground: Boolean,
        val hasActivityRecognition: Boolean,
        val wantsAlways: Boolean,
        val sdkInt: Int,
    )

    // Neither ACCESS_BACKGROUND_LOCATION nor ACTIVITY_RECOGNITION exists before
    // API 29 (Android 10) - requesting them below that level is an error.
    private const val MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION = 29

    /** The next permission set to request, or null when nothing is left. */
    fun nextRequest(grants: Grants): List<String>? = when {
        !grants.hasFineOrCoarse ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        // Requested on its OWN, never bundled with foreground location: Android
        // silently denies a combined foreground+background request from API 30.
        grants.wantsAlways && !grants.hasBackground &&
            grants.sdkInt >= MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION ->
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        // Must not be skipped just because location was granted - without AR the
        // motion machine degrades to speed + stationary-geofence (~200 m to re-engage).
        !grants.hasActivityRecognition && grants.sdkInt >= MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION ->
            listOf(Manifest.permission.ACTIVITY_RECOGNITION)

        else -> null
    }
}
