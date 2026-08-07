plugins {
    id("com.android.library") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    `maven-publish`
    signing
}

group = "dev.bgeo"
version = "0.1.2"

/** Public repository — POM `url`/`scm` and the licence links all point here. */
val PROJECT_URL = "https://github.com/dc-bgeo/android-background-geolocation"

android {
    // The engine AAR owns the "com.bgeo" namespace (R class, manifest
    // components); this facade namespaces its own R separately to avoid a
    // duplicate-namespace clash. Same reason the RN bridge uses com.bgeo.rn.
    namespace = "com.bgeo.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

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

    publishing {
        // Pinned before anything was published, so this can't default to
        // `dev.bgeo:sdk` (group + this module's Gradle path/name) — the
        // coordinate is `dev.bgeo:background-geolocation`, set explicitly
        // below. Sources and javadoc are Maven Central requirements; unlike
        // the closed engine, this facade publishes its REAL sources.
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin { jvmToolchain(17) }

afterEvaluate {
    // AGP registers the "release" software component lazily; this block must
    // run after project evaluation for `components["release"]` to exist.
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.bgeo"
                artifactId = "background-geolocation"
                version = project.version.toString()

                pom {
                    name.set("BGeo Android SDK")
                    description.set(
                        "Background geolocation for native Android: significant-motion tracking " +
                            "that survives process death, geofencing, a durable upload queue, and " +
                            "the same API surface as the BGeo React Native and Flutter SDKs.",
                    )
                    url.set(PROJECT_URL)
                    // The facade's own source is MIT (see LICENSE); the proprietary terms for
                    // the engine AAR it pulls in transitively live in LICENSE-BINARY.md and in
                    // the engine artifact's own POM.
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("$PROJECT_URL/blob/main/LICENSE")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            name.set("BGeo")
                            url.set("https://bgeo.dev")
                        }
                    }
                    scm {
                        url.set(PROJECT_URL)
                        connection.set("scm:git:$PROJECT_URL.git")
                        developerConnection.set("scm:git:ssh://git@github.com/dc-bgeo/android-background-geolocation.git")
                    }
                }
            }
        }
        repositories {
            // Staging area for a Central Portal bundle — see
            // tools/central-bundle.sh. Never a live remote: the bundle is
            // uploaded explicitly and the portal holds it until a human
            // presses Publish.
            maven {
                name = "CentralStaging"
                url = uri(layout.buildDirectory.dir("central-staging"))
            }
        }
    }

    signing {
        // Only arms for the Central staging publication — a local build on a
        // machine with no signing key must keep working.
        setRequired({ gradle.taskGraph.hasTask("publishReleasePublicationToCentralStagingRepository") })

        // CI has no gpg-agent to unlock, so it passes the key itself; a
        // developer machine keeps using the agent (see RELEASING.local.md,
        // and pinentry-mac if the passphrase prompt has nowhere to appear).
        val inMemoryKey = System.getenv("SIGNING_KEY")
        val inMemoryPassword = System.getenv("SIGNING_PASSWORD")
        if (!inMemoryKey.isNullOrBlank() && !inMemoryPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
        } else {
            useGpgCmd()
        }
        sign(publishing.publications["release"])
    }
}

dependencies {
    // api: the engine's types appear in this facade's own surface, and consumers
    // must resolve it transitively.
    api("dev.bgeo:bgeo-android:0.13.5")
    // api, not implementation: Flow appears in this facade's own public
    // surface (BackgroundGeolocation.locations and its seven siblings,
    // Geofences.kt's geofenceEvents/geofenceChanges) - implementation would
    // keep kotlinx-coroutines off a consumer's compile classpath, breaking
    // even the README's own quickstart. Same reasoning as activity below.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    // api: ActivityResultCaller appears in PermissionRequester's public constructor,
    // so consumers must resolve it transitively, same rule as bgeo-android above.
    api("androidx.activity:activity:1.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json to shadow AGP's mockable-android.jar stub on the unit-test
    // classpath (AGP places the stub jar last, so this wins). Required because
    // of isReturnDefaultValues above — see the comment on that flag.
    testImplementation("org.json:json:20240303")
}
