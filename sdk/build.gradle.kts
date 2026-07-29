plugins {
    id("com.android.library") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

group = "dev.bgeo"
version = "0.1.0"

android {
    // The engine AAR owns the "com.bgeo" namespace (R class, manifest
    // components); this facade namespaces its own R separately to avoid a
    // duplicate-namespace clash. Same reason the RN bridge uses com.bgeo.rn.
    namespace = "com.bgeo.sdk"
    compileSdk = 36

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnit() }
        // The engine's BGGeoEngine object eagerly touches Looper.getMainLooper()
        // in its class initializer; the stub android.jar throws on any call
        // that isn't mocked unless this is set, so a plain JVM reference to the
        // engine's public surface would otherwise fail before the test body runs.
        //
        // COUPLED WITH the org.json:json testImplementation below: this flag
        // applies to ALL of android.jar, not just Looper — including org.json,
        // whose stub JSONObject silently no-ops (put() returns null, getInt()
        // throws/returns 0) instead of doing real work. Tasks 3+ build typed
        // models, Config and the event hub on top of JSONObject and unit-test
        // them on the JVM; without the real org.json dependency shadowing the
        // stub, those tests would NPE or worse, pass vacuously. Removing either
        // setting alone breaks the suite: drop this flag and BGGeoEngine
        // reference throws; drop the testImplementation below and JSONObject
        // reverts to the silently-broken stub. See JsonRuntimeTest.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // api: the engine's types appear in this facade's own surface, and consumers
    // must resolve it transitively.
    api("dev.bgeo:bgeo-android:0.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json to shadow AGP's mockable-android.jar stub on the unit-test
    // classpath (AGP places the stub jar last, so this wins). Required because
    // of isReturnDefaultValues above — see the comment on that flag.
    testImplementation("org.json:json:20240303")
}
