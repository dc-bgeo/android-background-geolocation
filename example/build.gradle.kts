plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

android {
    namespace = "dev.bgeo.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bgeo.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnit() }
        // COUPLED WITH the org.json:json testImplementation below, and for the
        // same reasons as :sdk — see the long comment in sdk/build.gradle.kts.
        // This flag stubs ALL of android.jar, org.json included, whose stub
        // JSONObject silently no-ops. Every JSON decoding test in this module
        // would pass vacuously without the real dependency shadowing it.
        // Removing either setting alone breaks the suite. See JsonRuntimeTest.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    // Real org.json to shadow AGP's mockable-android.jar stub on the unit-test
    // classpath (AGP places the stub jar last, so this wins). COUPLED WITH
    // `unitTests.isReturnDefaultValues = true` above — see the comment on
    // that flag. Removing this dependency makes every JSON test in this
    // module pass vacuously against the stub JSONObject instead.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
