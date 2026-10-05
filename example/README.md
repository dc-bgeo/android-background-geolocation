# BGeo Android example console

A developer console for the BGeo Android SDK: three tabs (Map, Logs,
Settings) over a live engine. It is the Kotlin sibling of
`react-native/example` and `ios/Example` — same screens, same
schema-driven settings — and it doubles as the source of the
Android screenshots in the docs.

It is a debug tool, not a sample of minimal integration. If you want the
smallest possible "how do I use this SDK", read `../README.md` instead; if you
want to watch the engine's behaviour change as you turn knobs, run this.

## Build and run

```bash
cd android
./gradlew :example:installDebug        # build + install on the attached device
adb shell am start -n dev.bgeo.example/.MainActivity
./gradlew :example:testDebugUnitTest   # the module's unit tests
```

No API key of any kind is needed to build: the map is osmdroid over OSM
tiles, and the engine AAR is resolved from `android/libs`
(`settings.gradle.kts`'s file-backed Maven repo) until it goes to Maven
Central.

Everything stays on the device: locations, logs and geofences are kept
locally and shown in the app. The app uploads nothing — to try the SDK's HTTP
upload, set `url` (and optionally `authorization`) in your own app's `Config`.

## How it is wired

- `ExampleApplication.kt:30` — `BackgroundGeolocation.attach(this)` in
  `Application.onCreate`, because the system also starts this process
  headlessly for boot, geofence and service events (see
  `BackgroundGeolocation.attach`'s KDoc, `../sdk/.../BackgroundGeolocation.kt:84`).
  `AppContainer` (`:56`) holds the one `AppStore`/`ConfigStore`/`Geofences`
  the whole process shares.
- `ExampleApp.kt:125` — `Bootstrap` subscribes to all nine event streams
  FIRST, then calls `ready()`. That order is
  deliberate: the SDK's event hub buffers per event name until the first
  subscriber attaches, so subscribing late can lose launch-time events.
  It runs exactly once per process.
- `ExampleApp.kt:67` — `baseConfig`: the six keys `ready()` boots with,
  identical to the RN and iOS consoles. Every one of them must match its
  `ConfigSchema` default; `ExampleAppTest` fails if they drift, because that
  column is what Settings displays and what **Reset** pushes back.
- `LogUploader.kt` — the single logging entry point. Every line goes to both
  the Logs screen and the SDK's persisted log queue (which survives app
  kills). Nothing calls
  `AppStore.appendLog` directly.

## Things worth knowing

- **The Logs tab shows two streams merged.** Blue-ish dot-namespaced lines
  (`service.start`, `motion.moving`) come from the engine's own persisted log;
  the rest are this app's event subscriptions. Native lines only exist at the
  configured `logLevel` — the app boots at INFO.
- **The app icon is load-bearing.** The engine's foreground-service
  notification falls back to `applicationInfo.icon`
  (`BGGeoEngine.kt:544`); a module with no icon makes `startForeground` throw
  and the process is killed the moment tracking starts. Hence
  `res/drawable/ic_launcher.xml`.
- **Redaction is by key, and only by key.** `LogUploader` strips values under
  credential-shaped keys (`accessToken`, `authorization`, …) out of every log
  payload, recursively, and scrubs those same literals out of the free-text
  message. What it cannot see inside is an opaque string: `onHttp`'s
  `responseText` is the raw response body with no keys to match, so a
  credential echoed back by a server inside an error body would reach the log
  unredacted. That is a limit of the approach, not a defect in it — it has
  never been claimed as covered. Keep credentials out of server error bodies.
- **The map's from/to range filters the local track.** `History.kt` filters
  the in-memory buffer (capped at 2000 points) — there is no server history.
- **Compose UI is not unit-tested** in this module — there is no
  instrumentation or Robolectric harness. Logic lives in plain Kotlin files
  (`ConfigSchema`, `ConfigStore`, `MapRebuild`, `LogsScreenLogic`, …) which
  are; rendering is verified by running the app.
