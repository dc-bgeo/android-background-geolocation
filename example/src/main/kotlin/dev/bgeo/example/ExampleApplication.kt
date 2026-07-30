package dev.bgeo.example

import android.app.Application
import android.content.Context
import android.os.Build
import com.bgeo.sdk.BackgroundGeolocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point.
 *
 * `attach()` lives HERE, not in `MainActivity`: the system restarts this
 * process for boot, geofence and service events, and `Application.onCreate`
 * is the only hook that runs in every one of them (see
 * `BackgroundGeolocation.attach`'s own KDoc — skipping the hub-attach step is
 * the bug the iOS facade shipped with, where the engine silently discarded
 * every event until something happened to subscribe). The engine AAR merges
 * its own `com.bgeo.BootReceiver` into this app's manifest, so a
 * boot-triggered start happens with no Activity at all. For a console
 * demonstrating a BACKGROUND geolocation SDK, attaching only from an Activity
 * would quietly demonstrate the failure class the SDK's docs warn against.
 *
 * Whether the engine's own boot-resume path additionally depends on the
 * facade having attached is not verifiable from this repo — the engine ships
 * as a compiled AAR. What is verifiable, and what this placement guarantees,
 * is that the facade's event hub is claimed on every process start.
 */
class ExampleApplication : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Process-lifetime scope for [Bootstrap] and the event handlers'
     * follow-up work (the geofence re-sync on `onGeofencesChange`). Not tied
     * to an Activity: a config change must not cancel `ready()` half way
     * through. `SupervisorJob` so one failed child doesn't take the scope
     * down with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        BackgroundGeolocation.attach(this)
        container = AppContainer(this)
        scope.launch { container.bootstrap(scope).run() }
    }
}

/**
 * The app's one set of long-lived objects, built once per process and shared
 * by every screen. Deliberately not per-Activity: `AppStore`'s log/point
 * buffers and `DeviceLink`'s state must survive a rotation, and two
 * `DeviceLink`s racing the same `SharedPreferences` would defeat the
 * refresh serialisation `DeviceLink.authorizedFetch` implements.
 */
class AppContainer(context: Context) {
    private val storage: Storage = SharedPreferencesStorage(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    val store = AppStore()
    val logUploader = LogUploader(store)
    val configStore = ConfigStore(storage)
    val deviceLink = DeviceLink(
        http = HttpUrlConnectionHttp(),
        storage = storage,
        deviceInfo = deviceInfo(context),
        store = store,
    )
    val geofences = Geofences(store = store, deviceLink = deviceLink)

    fun bootstrap(scope: CoroutineScope) = Bootstrap(
        store = store,
        configStore = configStore,
        deviceLink = deviceLink,
        geofences = geofences,
        logUploader = logUploader,
        scope = scope,
    )

    private companion object {
        const val PREFS_NAME = "bgeo.example"
    }
}

/**
 * `DeviceInfo` is built here rather than read inside `DeviceLink`, because
 * this module's unit tests stub all of `android.jar` — `Build.MODEL` and
 * friends return stub values there. See `DeviceInfo`'s doc comment.
 */
@Suppress("DEPRECATION") // getPackageInfo(String, Int) — the API 33 replacement needs minSdk 33.
private fun deviceInfo(context: Context): DeviceInfo = DeviceInfo(
    model = Build.MODEL ?: "android",
    osVersion = Build.VERSION.RELEASE ?: "",
    appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0",
)
