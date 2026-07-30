# BGeo Android example console

A developer console for the BGeo Android SDK: three tabs (Map, Logs,
Settings) over a live engine. It is the Kotlin sibling of
`react-native/example` and `ios/Example` — same screens, same device-link
flow, same schema-driven settings — and it doubles as the source of the
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

**A debuggable build runs unlicensed.** The engine logs
`BGeo — unlicensed evaluation (debuggable build, LICENSE_MISSING)` at start-up
and works normally — see `core/android/engine/.../BGGeoLicenseManager.kt:18`.
A release build of your own app needs a real key.

## Linking to the web console

Tracking works unlinked; linking adds server upload, geofence sync and the
log stream you can view from a browser.

1. In the BGeo web console: **Dashboard → Registration codes**, create a code.
2. In the app: **Settings → Debug console**, keep `https://app.bgeo.dev` as
   the server, paste the code, tap **Link device**.

What that does: `POST /device/register` exchanges the code for a device id and
a JWT pair, which are persisted and pushed into the engine as `url`, `logUrl`
and an `authorization` block (`src/main/kotlin/dev/bgeo/example/DeviceLink.kt`).
From then on the engine uploads locations and logs itself, refreshing the
tokens natively — the app persists each rotated pair from `onAuthorization` so
its own API calls keep working too.

**Unlink** clears all of that through the engine's clear sentinel, not empty
strings, so it stops uploading to a server it is no longer linked to.

## How it is wired

- `ExampleApplication.kt:31` — `BackgroundGeolocation.attach(this)` in
  `Application.onCreate`, because the system also starts this process
  headlessly for boot, geofence and service events (see
  `BackgroundGeolocation.attach`'s KDoc, `../sdk/.../BackgroundGeolocation.kt:73`).
  `AppContainer` (`:59`) holds the one `AppStore`/`DeviceLink`/`ConfigStore`
  the whole process shares.
- `ExampleApp.kt:126` — `Bootstrap` subscribes to all nine event streams
  FIRST, then restores a persisted link, then calls `ready()`. That order is
  deliberate: the SDK's event hub buffers per event name until the first
  subscriber attaches, so subscribing late can lose launch-time events.
  It runs exactly once per process.
- `ExampleApp.kt:67` — `baseConfig`: the six keys `ready()` boots with,
  identical to the RN and iOS consoles. Every one of them must match its
  `ConfigSchema` default; `ExampleAppTest` fails if they drift, because that
  column is what Settings displays and what **Reset** pushes back.
- `LogUploader.kt` — the single logging entry point. Every line goes to both
  the Logs screen and the SDK's persisted log queue (which survives app kills
  and uploads to `/device/logs` once linked). Nothing calls
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
- **`maxBatchSize` reads wrong in Settings after linking.** `DeviceLink`
  pushes `maxBatchSize = 50` directly to the engine, outside `ConfigStore`'s
  bookkeeping, so Settings keeps showing the schema default while the engine
  runs 50. Same on iOS. Unresolved by design — it is an owner decision whether
  to route those pushes through the overrides or relabel the column.
- **No history range on the map.** `History.kt` implements the server/local
  history query the RN console's from/to bar uses, but no screen consumes it
  yet; the map renders the live in-memory track (capped at 2000 points).
- **Compose UI is not unit-tested** in this module — there is no
  instrumentation or Robolectric harness. Logic lives in plain Kotlin files
  (`ConfigSchema`, `ConfigStore`, `MapRebuild`, `LogsScreenLogic`, …) which
  are; rendering is verified by running the app.
