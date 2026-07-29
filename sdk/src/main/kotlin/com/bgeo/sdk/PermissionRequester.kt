package com.bgeo.sdk

import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    // Guards `onResult`/`launch` against a second concurrent `request()` call
    // (e.g. one fired from onResume racing an "Enable tracking" button tap):
    // without it, the second call's launch overwrites onResult and the
    // system's callback for the FIRST call resumes the SECOND continuation,
    // leaving the first caller suspended forever.
    private val mutex = Mutex()

    /**
     * Runs [PermissionPlan]'s escalation to completion, one request at a
     * time, advancing past each stage once it has been attempted regardless
     * of outcome - see [PermissionPlan]'s class doc for why a denial does not
     * stop the chain. Afterwards, resumes tracking (covers a `start()` that
     * earlier bailed on missing permission) and emits a provider-change so
     * subscribers see the final grant state - matching the RN/Flutter
     * bridges' `finishPermission()`, minus their `maybeShowAuthorizationAlert()`
     * dialog, which this facade does not port.
     */
    suspend fun request(): AuthorizationStatus = mutex.withLock {
        val engine = BackgroundGeolocation.engine
        var attempted = emptySet<PermissionPlan.Stage>()
        while (true) {
            val step = PermissionPlan.nextRequest(currentGrants(engine), attempted) ?: break
            launchAndAwait(step.permissions)
            attempted = attempted + step.stage
        }
        engine.resumeTrackingIfEnabled()
        engine.emitProviderChange()
        AuthorizationStatus.from(engine.numericStatus())
    }

    private suspend fun launchAndAwait(permissions: List<String>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            onResult = {
                onResult = null
                continuation.resume(Unit)
            }
            launcher.launch(permissions.toTypedArray())
        }
    }

    private fun currentGrants(engine: Engine) = PermissionPlan.Grants(
        hasFineOrCoarse = engine.hasFineOrCoarse(),
        hasBackground = engine.hasBackground(),
        hasActivityRecognition = engine.hasActivityRecognition(),
        wantsAlways = engine.wantsAlways(),
        sdkInt = Build.VERSION.SDK_INT,
    )
}
