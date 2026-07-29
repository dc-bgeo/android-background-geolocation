package com.bgeo.sdk

import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Activity-bound plumbing for the escalating permission flow. What to request
 * next is decided entirely by [PermissionPlan] (pure, unit-tested); this class
 * is only the loop that drives it through a real `ActivityResultLauncher` and
 * reports the outcome back to the engine.
 *
 * The app MUST construct this before the [caller] reaches `STARTED` (typically
 * in an `Activity`/`Fragment`'s `onCreate`) - `ActivityResultCaller.
 * registerForActivityResult` requires that.
 *
 * Not unit-tested: `unitTests.isReturnDefaultValues` stubs all of `android.jar`,
 * so nothing that touches a real `Activity`/`ActivityResultCaller` is
 * exercisable on the JVM here - device-verified in a later phase.
 */
class PermissionRequester(private val caller: ActivityResultCaller) {

    private var onResult: ((Map<String, Boolean>) -> Unit)? = null

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> onResult?.invoke(result) }

    /**
     * Runs [PermissionPlan]'s escalation to completion, one request at a time.
     * Stops early if a step comes back not fully granted, rather than
     * re-prompting for the same denied permission forever (device-verified
     * only - see the task report for why this is not a straight port of the
     * RN/Flutter bridges' denial handling).
     *
     * Mirrors those bridges' `finishPermission()`: resumes tracking (covers a
     * `start()` that earlier bailed on missing permission) before emitting a
     * provider-change, so subscribers see the final grant state.
     */
    suspend fun request(): AuthorizationStatus {
        val engine = BackgroundGeolocation.engine
        while (true) {
            val next = PermissionPlan.nextRequest(currentGrants(engine)) ?: break
            val result = launchAndAwait(next)
            if (next.any { permission -> result[permission] != true }) break
        }
        engine.resumeTrackingIfEnabled()
        engine.emitProviderChange()
        return AuthorizationStatus.from(engine.numericStatus())
    }

    private suspend fun launchAndAwait(permissions: List<String>): Map<String, Boolean> =
        suspendCancellableCoroutine { continuation ->
            onResult = { result ->
                onResult = null
                continuation.resume(result)
            }
            launcher.launch(permissions.toTypedArray())
        }

    private fun currentGrants(engine: Engine) = PermissionPlan.Grants(
        hasFineOrCoarse = engine.hasFineOrCoarse(),
        hasBackground = engine.hasBackground(),
        hasActivityRecognition = engine.hasActivityRecognition(),
        wantsAlways = engine.wantsAlways(),
        sdkInt = Build.VERSION.SDK_INT,
    )
}
