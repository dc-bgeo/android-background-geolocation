package com.bgeo.sdk

import android.Manifest

/**
 * Pure, Android-free decision: given the current grants and which stages have
 * already been attempted this escalation, what permission set to request
 * next. Ported from the escalation order in
 * `react-native/android/src/main/java/com/bgeo/rn/BackgroundGeolocationModule.kt:282-320`.
 *
 * [attempted] exists because [Grants] alone cannot distinguish "never asked"
 * from "asked and denied" - without it, a denied FOREGROUND grant would make
 * `nextRequest` return the same FOREGROUND request forever, permanently
 * hiding ACTIVITY_RECOGNITION behind it. The bridge above never re-prompts a
 * stage once it has been attempted, regardless of outcome (its
 * `finishPermission()` comment even documents this as a bug it fixed:
 * resolving on BACKGROUND alone used to skip the ACTIVITY_RECOGNITION
 * request). Carrying `attempted` as a parameter reproduces that without
 * making this function stateful or lying about real grant state.
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

    enum class Stage { FOREGROUND, BACKGROUND, ACTIVITY_RECOGNITION }

    data class Step(val stage: Stage, val permissions: List<String>)

    // Neither ACCESS_BACKGROUND_LOCATION nor ACTIVITY_RECOGNITION exists before
    // API 29 (Android 10) - requesting them below that level is an error.
    private const val MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION = 29

    /**
     * The next step to request, or null when nothing is left. [attempted]
     * accumulates one [Stage] per call to keep the escalation moving forward
     * even when a stage comes back denied - see the class doc.
     */
    fun nextRequest(grants: Grants, attempted: Set<Stage> = emptySet()): Step? = when {
        !grants.hasFineOrCoarse && Stage.FOREGROUND !in attempted ->
            Step(
                Stage.FOREGROUND,
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )

        // Requested on its OWN, never bundled with foreground location: Android
        // silently denies a combined foreground+background request from API 30.
        // Gated on hasFineOrCoarse (not just "FOREGROUND attempted"): background
        // location without foreground is meaningless, and this is also what
        // makes the bridge skip BACKGROUND after a foreground denial while
        // still going on to ask for ACTIVITY_RECOGNITION.
        grants.hasFineOrCoarse && grants.wantsAlways && !grants.hasBackground &&
            grants.sdkInt >= MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION && Stage.BACKGROUND !in attempted ->
            Step(Stage.BACKGROUND, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

        // Must not be skipped just because location was granted - without AR the
        // motion machine degrades to speed + stationary-geofence (~200 m to re-engage).
        !grants.hasActivityRecognition && grants.sdkInt >= MIN_SDK_FOR_BACKGROUND_AND_ACTIVITY_RECOGNITION &&
            Stage.ACTIVITY_RECOGNITION !in attempted ->
            Step(Stage.ACTIVITY_RECOGNITION, listOf(Manifest.permission.ACTIVITY_RECOGNITION))

        else -> null
    }
}
