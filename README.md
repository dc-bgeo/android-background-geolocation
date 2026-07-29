# BGeo Android SDK

An open Kotlin facade over the closed-source BGeo background-geolocation
engine. This module (`com.bgeo.sdk`) is the public API a native Android app
integrates against; it talks to the closed `dev.bgeo:bgeo-android` engine AAR
underneath, and its method/event names deliberately mirror
`react-native/src/index.ts` so a developer moving between BGeo's SDKs finds
the same vocabulary.

## Installation

Gradle coordinate: `dev.bgeo:sdk:0.1.0` (group `dev.bgeo`, module `sdk`).

This module isn't published anywhere yet — Maven Central packaging lands in
phase 2. For now, the engine AAR it depends on is vendored as a local Maven
repo under `libs/` (wired in `settings.gradle.kts` via
`maven { url = uri("${rootDir}/libs") }`); a consuming app in this phase
should include this `sdk` module directly (e.g. via `includeBuild`) rather
than resolve it from a remote repository.

## Integration

### 1. `Application.onCreate` is mandatory

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackgroundGeolocation.attach(this)
    }
}
```

Android restarts this process for boot, geofence and service events —
`Application.onCreate` is the only hook that reliably runs in every one of
them. A listener registered only in an `Activity` will miss every event that
arrives while no `Activity` is alive. This is the single most important
integration fact for this SDK, and the reason it needs no separate headless
layer: `attach` wires the engine to the process once, for the process's
whole lifetime.

### 2. `ACCESS_BACKGROUND_LOCATION` must be declared by your app

The engine AAR deliberately omits this permission from its own manifest
(`core/android/engine/src/main/AndroidManifest.xml:4-6`) — Google Play policy
requires the *app* to own both the manifest declaration and the Play Console
background-location disclosure. Add it to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

### 3. Licence key

The licence key is **not** a `Config` property. It's read from your app's
manifest at launch:

```xml
<application>
    <meta-data android:name="com.bgeo.license" android:value="BGEO1..." />
</application>
```

(source of the exact shape: `react-native/src/types.ts:130-136`). In a
release build, a missing/invalid/expired/mismatched key makes `ready()`/
`start()` reject with a `LICENSE_*` `BGeoException`. A **debuggable** build
always runs unlicensed (evaluation mode) regardless of the key — if tracking
silently works while debugging but rejects in release, check the key before
assuming it's broken.

### 4. Permissions

Construct a `PermissionRequester` in your `Activity`'s (or `Fragment`'s)
`onCreate`, before it reaches `STARTED` — `ActivityResultCaller.
registerForActivityResult` (which `PermissionRequester` calls internally)
requires that:

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var permissionRequester: PermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequester = PermissionRequester(this)
    }
}
```

`requestPermission` then escalates through foreground location, background
location and activity recognition **one stage at a time**, never bundling
foreground and background into a single request — Android silently denies a
combined foreground+background request from API 30 onward. A denial at one
stage does not stop the escalation; it moves on to the next permission so a
user who denies "Always" location can still grant activity recognition.

### 5. Quickstart

```kotlin
lifecycleScope.launch {
    BackgroundGeolocation.ready(
        Config(desiredAccuracy = DesiredAccuracy.HIGH.value, distanceFilter = 10.0),
    )
    BackgroundGeolocation.requestPermission(permissionRequester)
    BackgroundGeolocation.start()
}

lifecycleScope.launch {
    BackgroundGeolocation.locations.collect { location ->
        Log.d("BGeo", "${location.coords.latitude}, ${location.coords.longitude}")
    }
}
```

(`attach` runs once in `Application.onCreate`, as shown above — it is not
part of this per-`Activity` flow.)

## Running tests

Full suite:

```
./gradlew :sdk:test
```

To run a single test class or method, use the variant task directly —
`:sdk:test` is an AGP aggregate task that rejects `--tests`:

```
./gradlew :sdk:testDebugUnitTest --tests '*ConfigTest*'
```
