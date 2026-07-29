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
}
